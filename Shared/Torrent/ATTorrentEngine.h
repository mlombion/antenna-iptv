#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// One file inside a torrent.
@interface ATTorrentFile : NSObject
@property (nonatomic, readonly) int index;
@property (nonatomic, readonly, copy) NSString *name;
/// Path relative to the torrent's save folder.
@property (nonatomic, readonly, copy) NSString *path;
@property (nonatomic, readonly) int64_t size;
/// Where the file starts within the torrent's data, in bytes.
@property (nonatomic, readonly) int64_t offset;
@end

@interface ATTorrentStatus : NSObject
@property (nonatomic, readonly) BOOL hasMetadata;
@property (nonatomic, readonly) int peers;
@property (nonatomic, readonly) int seeds;
@property (nonatomic, readonly) int downloadRate;
/// Bytes of wanted data on disk.
@property (nonatomic, readonly) int64_t totalDone;
@end

/// A thin Objective-C face on libtorrent for Swift: one session for the app, torrents addressed by info hash.
@interface ATTorrentEngine : NSObject
@property (class, nonatomic, readonly) ATTorrentEngine *shared;

/// Adds a magnet link, downloading in order into `savePath`. Returns NO with `error` if the link is bad.
- (BOOL)addMagnet:(NSString *)magnet hash:(NSString *)hash savePath:(NSString *)savePath error:(NSError **)error;
- (nullable ATTorrentStatus *)statusForHash:(NSString *)hash;
/// Empty until the torrent's metadata has arrived from peers.
- (NSArray<ATTorrentFile *> *)filesForHash:(NSString *)hash;
- (int)pieceLengthForHash:(NSString *)hash;
/// Downloads only this file.
- (void)selectFile:(int)index hash:(NSString *)hash;
- (BOOL)havePiece:(int)piece hash:(NSString *)hash;
- (void)setDeadline:(int)milliseconds piece:(int)piece hash:(NSString *)hash;
- (void)clearDeadlinesForHash:(NSString *)hash;
/// Low-storage streaming: download nothing in the file except `count` pieces from `first`.
- (void)keepOnlyPieces:(int)first count:(int)count hash:(NSString *)hash;
- (void)setTopPriorityPiece:(int)piece hash:(NSString *)hash;
/// Low-storage streaming: deletes everything downloaded so far and starts the torrent again in `savePath`,
/// wanting only `count` pieces from `first` (metadata is kept, so nothing is fetched from peers first).
- (BOOL)restartHash:(NSString *)hash savePath:(NSString *)savePath file:(int)file first:(int)first count:(int)count;
/// Stops the torrent and deletes its data.
- (void)removeHash:(NSString *)hash;
@end

NS_ASSUME_NONNULL_END
