package elgatopro300.bbsfbx.model.fbx.loaders;

import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Skips re-running the native Assimp import and FBX -> BOBJData conversion
 * when a model's .fbx file content hasn't actually changed since it was
 * last loaded - the common case when "reload models" is pressed after
 * changing something unrelated (a different model, a script, an animation
 * clip), not this particular FBX file.
 *
 * <p>Reusing the cached {@link BOBJData} (and its armatures) across loads is
 * safe: {@code BOBJArmature#initArmature} guards itself with an
 * {@code initialized} flag, so calling {@code BOBJData#initiateArmatures}
 * again on the same cached object is a no-op rather than a double init. And
 * BBS FS's model system already assumes one shared armature/model instance
 * at a time for a given id (that's how {@code ModelManager.getModel} works
 * normally) - reusing the same objects across sequential reloads (never
 * concurrently, since {@code ModelManager.reload()} clears the old instance
 * out before anything new loads) doesn't introduce anything new there.</p>
 *
 * <p>Keyed by the model's fbx {@link mchorse.bbs_mod.resources.Link#path},
 * guarded by a content hash so an actually-edited file is never served
 * stale data.</p>
 */
public final class FBXModelLoadCache
{
    private static final class Entry
    {
        final long hash;
        final BOBJData data;
        final Set<String> shapeKeyNames;

        Entry(long hash, BOBJData data, Set<String> shapeKeyNames)
        {
            this.hash = hash;
            this.data = data;
            this.shapeKeyNames = shapeKeyNames;
        }
    }

    /** Small holder so callers get both cached pieces from one lookup. */
    public static final class Cached
    {
        public final BOBJData data;
        public final Set<String> shapeKeyNames;

        private Cached(BOBJData data, Set<String> shapeKeyNames)
        {
            this.data = data;
            this.shapeKeyNames = shapeKeyNames;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private FBXModelLoadCache() {}

    /**
     * Cheap, non-cryptographic change-detection hash - this only needs to
     * catch "did this file's bytes change", not resist tampering. CRC32
     * over the content, mixed with the length so truncation/extension that
     * happens to preserve the running checksum still changes the result.
     */
    public static long hash(byte[] bytes)
    {
        CRC32 crc = new CRC32();
        crc.update(bytes);

        return (crc.getValue() << 1) ^ bytes.length;
    }

    public static Cached get(String key, long hash)
    {
        Entry entry = CACHE.get(key);

        if (entry == null || entry.hash != hash)
        {
            return null;
        }

        return new Cached(entry.data, entry.shapeKeyNames);
    }

    public static void put(String key, long hash, BOBJData data, Set<String> shapeKeyNames)
    {
        CACHE.put(key, new Entry(hash, data, shapeKeyNames));
    }

    /** Drops a single cached entry - not currently called, but here if you want a manual "force full reimport" hook. */
    public static void invalidate(String key)
    {
        CACHE.remove(key);
    }
}
