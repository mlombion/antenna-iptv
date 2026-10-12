// Must match how Vendor/libtorrent.xcframework was built (Tools/build_libtorrent.sh).
#define BOOST_ASIO_ENABLE_CANCELIO 1
#define BOOST_ASIO_HAS_STD_CHRONO 1
#define BOOST_ASIO_NO_DEPRECATED 1
#define BOOST_EXCEPTION_DISABLE 1
#define TORRENT_NO_DEPRECATE 1

#import "ATTorrentEngine.h"

#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <unistd.h>
#include <algorithm>
#include <vector>
#include <libtorrent/session.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/posix_disk_io.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/announce_entry.hpp>
#include <libtorrent/peer_info.hpp>

@implementation ATTorrentFile
- (instancetype)initWithIndex:(int)index name:(NSString *)name path:(NSString *)path size:(int64_t)size offset:(int64_t)offset {
    if ((self = [super init])) { _index = index; _name = [name copy]; _path = [path copy]; _size = size; _offset = offset; }
    return self;
}
@end

@implementation ATTorrentStatus
- (instancetype)initWithMetadata:(BOOL)m peers:(int)p seeds:(int)s rate:(int)r done:(int64_t)d {
    if ((self = [super init])) { _hasMetadata = m; _peers = p; _seeds = s; _downloadRate = r; _totalDone = d; }
    return self;
}
@end

@implementation ATTorrentEngine {
    std::unique_ptr<lt::session> _session;
    std::map<std::string, lt::torrent_handle> _handles;
    std::mutex _lock;
}

+ (ATTorrentEngine *)shared {
    static ATTorrentEngine *engine;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ engine = [[ATTorrentEngine alloc] init]; });
    return engine;
}

- (lt::session &)session {
    if (!_session) {
        lt::settings_pack pack;
        pack.set_int(lt::settings_pack::alert_mask, lt::alert_category::error);
        pack.set_int(lt::settings_pack::connections_limit, 200);
        pack.set_bool(lt::settings_pack::enable_dht, true);
        pack.set_str(lt::settings_pack::dht_bootstrap_nodes,
                     "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881");
        // No local-network discovery or router port mapping: on iPhone those would ask for local network access.
        pack.set_bool(lt::settings_pack::enable_lsd, false);
        pack.set_bool(lt::settings_pack::enable_upnp, false);
        pack.set_bool(lt::settings_pack::enable_natpmp, false);
        lt::session_params params(std::move(pack));
        params.disk_io_constructor = lt::posix_disk_io_constructor;
        _session = std::make_unique<lt::session>(std::move(params));
    }
    return *_session;
}

- (lt::torrent_handle)handle:(NSString *)hash {
    std::lock_guard<std::mutex> g(_lock);
    auto it = _handles.find(hash.UTF8String);
    return it == _handles.end() ? lt::torrent_handle() : it->second;
}

- (BOOL)addMagnet:(NSString *)magnet hash:(NSString *)hash savePath:(NSString *)savePath error:(NSError **)error {
    lt::error_code ec;
    lt::add_torrent_params p = lt::parse_magnet_uri(magnet.UTF8String, ec);
    if (ec) {
        if (error) *error = [NSError errorWithDomain:@"ATTorrent" code:1 userInfo:@{NSLocalizedDescriptionKey: @(ec.message().c_str())}];
        return NO;
    }
    p.save_path = savePath.UTF8String;
    p.flags |= lt::torrent_flags::sequential_download;
    std::lock_guard<std::mutex> g(_lock);
    lt::torrent_handle h = [self session].add_torrent(std::move(p), ec);
    if (ec || !h.is_valid()) {
        if (error) *error = [NSError errorWithDomain:@"ATTorrent" code:2 userInfo:@{NSLocalizedDescriptionKey: @(ec.message().c_str())}];
        return NO;
    }
    _handles[hash.UTF8String] = h;
    // Nobody reads alerts; keep the queue from growing.
    std::vector<lt::alert *> alerts;
    [self session].pop_alerts(&alerts);
    return YES;
}

- (ATTorrentStatus *)statusForHash:(NSString *)hash {
    lt::torrent_handle h = [self handle:hash];
    if (!h.is_valid()) return nil;
    lt::torrent_status s;
    try { s = h.status(); } catch (...) { return nil; }
    return [[ATTorrentStatus alloc] initWithMetadata:s.has_metadata peers:s.num_peers seeds:s.num_seeds rate:s.download_payload_rate done:s.total_wanted_done];
}

- (NSArray<ATTorrentFile *> *)filesForHash:(NSString *)hash {
    lt::torrent_handle h = [self handle:hash];
    if (!h.is_valid()) return @[];
    std::shared_ptr<const lt::torrent_info> info;
    try { info = h.torrent_file(); } catch (...) { return @[]; }
    if (!info) return @[];
    const lt::file_storage &fs = info->files();
    NSMutableArray *out = [NSMutableArray arrayWithCapacity:fs.num_files()];
    for (lt::file_index_t i : fs.file_range()) {
        NSString *name = [NSString stringWithUTF8String:std::string(fs.file_name(i)).c_str()] ?: @"";
        NSString *path = [NSString stringWithUTF8String:fs.file_path(i).c_str()] ?: name;
        [out addObject:[[ATTorrentFile alloc] initWithIndex:static_cast<int>(i) name:name path:path
                                                       size:fs.file_size(i) offset:fs.file_offset(i)]];
    }
    return out;
}

- (int)pieceLengthForHash:(NSString *)hash {
    try {
        lt::torrent_handle h = [self handle:hash];
        auto info = h.is_valid() ? h.torrent_file() : nullptr;
        return info ? info->piece_length() : 0;
    } catch (...) { return 0; }
}

- (void)selectFile:(int)index hash:(NSString *)hash {
    try {
    lt::torrent_handle h = [self handle:hash];
    auto info = h.is_valid() ? h.torrent_file() : nullptr;
    if (!info) return;
    std::vector<lt::download_priority_t> prio(static_cast<size_t>(info->num_files()), lt::dont_download);
    if (index >= 0 && index < info->num_files()) prio[static_cast<size_t>(index)] = lt::default_priority;
    h.prioritize_files(prio);
    } catch (...) {}
}

- (BOOL)havePiece:(int)piece hash:(NSString *)hash {
    lt::torrent_handle h = [self handle:hash];
    // Low-storage mode swaps the torrent while players read; a handle on its way out just means "not yet".
    try { return h.is_valid() && h.have_piece(lt::piece_index_t(piece)); } catch (...) { return NO; }
}

- (void)setDeadline:(int)milliseconds piece:(int)piece hash:(NSString *)hash {
    lt::torrent_handle h = [self handle:hash];
    try { if (h.is_valid()) h.set_piece_deadline(lt::piece_index_t(piece), milliseconds); } catch (...) {}
}

- (void)clearDeadlinesForHash:(NSString *)hash {
    lt::torrent_handle h = [self handle:hash];
    try { if (h.is_valid()) h.clear_piece_deadlines(); } catch (...) {}
}

static std::vector<lt::download_priority_t> windowPriorities(int pieces, int first, int count) {
    std::vector<lt::download_priority_t> prio(static_cast<size_t>(pieces), lt::dont_download);
    for (int p = std::max(0, first); p < std::min(pieces, first + count); ++p) prio[static_cast<size_t>(p)] = lt::top_priority;
    return prio;
}

- (void)keepOnlyPieces:(int)first count:(int)count hash:(NSString *)hash {
    lt::torrent_handle h = [self handle:hash];
    try {
        auto info = h.is_valid() ? h.torrent_file() : nullptr;
        if (info) h.prioritize_pieces(windowPriorities(info->num_pieces(), first, count));
    } catch (...) {}
}

- (void)setTopPriorityPiece:(int)piece hash:(NSString *)hash {
    lt::torrent_handle h = [self handle:hash];
    try { if (h.is_valid()) h.piece_priority(lt::piece_index_t(piece), lt::top_priority); } catch (...) {}
}

- (BOOL)restartHash:(NSString *)hash savePath:(NSString *)savePath file:(int)file first:(int)first count:(int)count {
    lt::torrent_handle old = [self handle:hash];
    std::shared_ptr<const lt::torrent_info> info;
    try { info = old.is_valid() ? old.torrent_file() : nullptr; } catch (...) {}
    if (!info || !_session) return NO;
    lt::info_hash_t ih = info->info_hashes();
    // The metadata has no trackers (they came with the magnet link), so carry them and the connected peers over.
    std::vector<std::string> trackers;
    std::vector<lt::tcp::endpoint> peers;
    try {
        for (auto const &t : old.trackers()) trackers.push_back(t.url);
        std::vector<lt::peer_info> info_peers;
        old.get_peer_info(info_peers);
        for (auto const &pi : info_peers) peers.push_back(pi.ip);
    } catch (...) {}
    _session->remove_torrent(old, lt::session::delete_files);
    // The same torrent can't be added again until the old one is gone.
    for (int i = 0; i < 100 && _session->find_torrent(ih.get_best()).is_valid(); ++i) usleep(50000);
    lt::add_torrent_params p;
    p.ti = std::make_shared<lt::torrent_info>(*info);
    p.save_path = savePath.UTF8String;
    p.flags |= lt::torrent_flags::sequential_download;
    p.file_priorities.assign(static_cast<size_t>(info->num_files()), lt::dont_download);
    if (file >= 0 && file < info->num_files()) p.file_priorities[static_cast<size_t>(file)] = lt::default_priority;
    p.piece_priorities = windowPriorities(info->num_pieces(), first, count);
    p.trackers = trackers;
    p.peers = peers;
    lt::error_code ec;
    std::lock_guard<std::mutex> g(_lock);
    lt::torrent_handle h = _session->add_torrent(std::move(p), ec);
    if (ec || !h.is_valid()) { _handles.erase(hash.UTF8String); return NO; }
    _handles[hash.UTF8String] = h;
    return YES;
}

- (void)removeHash:(NSString *)hash {
    lt::torrent_handle h;
    {
        std::lock_guard<std::mutex> g(_lock);
        auto it = _handles.find(hash.UTF8String);
        if (it == _handles.end()) return;
        h = it->second;
        _handles.erase(it);
    }
    if (_session && h.is_valid()) _session->remove_torrent(h, lt::session::delete_files);
}

@end
