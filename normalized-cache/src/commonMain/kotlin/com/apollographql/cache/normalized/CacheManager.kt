package com.apollographql.cache.normalized

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Executable
import com.apollographql.apollo.api.Fragment
import com.apollographql.apollo.api.Operation
import com.apollographql.cache.normalized.CacheManager.Companion.ALL_KEYS
import com.apollographql.cache.normalized.api.CacheHeaders
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheKeyGenerator
import com.apollographql.cache.normalized.api.CacheResolver
import com.apollographql.cache.normalized.api.DataWithErrors
import com.apollographql.cache.normalized.api.DefaultEmbeddedFieldsProvider
import com.apollographql.cache.normalized.api.DefaultFieldKeyGenerator
import com.apollographql.cache.normalized.api.DefaultMaxAgeProvider
import com.apollographql.cache.normalized.api.DefaultRecordMerger
import com.apollographql.cache.normalized.api.EmbeddedFieldsProvider
import com.apollographql.cache.normalized.api.EmptyMetadataGenerator
import com.apollographql.cache.normalized.api.FieldKeyGenerator
import com.apollographql.cache.normalized.api.MaxAgeProvider
import com.apollographql.cache.normalized.api.MetadataGenerator
import com.apollographql.cache.normalized.api.NormalizedCache
import com.apollographql.cache.normalized.api.NormalizedCacheFactory
import com.apollographql.cache.normalized.api.Record
import com.apollographql.cache.normalized.api.RecordMerger
import com.apollographql.cache.normalized.api.TypePolicyCacheKeyGenerator
import com.apollographql.cache.normalized.internal.DefaultCacheManager
import com.benasher44.uuid.Uuid
import kotlinx.coroutines.flow.SharedFlow
import kotlin.reflect.KClass

/**
 * CacheManager exposes a high-level API to access a [com.apollographql.cache.normalized.api.NormalizedCache].
 *
 * Although all operations are `suspend` functions, they may **suspend** or **block** the thread depending on the underlying cache
 * implementation. For example, the SQL cache implementation on Android will **block** the thread while accessing the disk. As such,
 * these operations **must not** run on the main thread. You can enclose them in a [kotlinx.coroutines.withContext] block with a
 * `Dispatchers.IO` context to ensure that they run on a background thread.
 *
 * Note that changes are not automatically published - call [publish] to notify any watchers.
 */
interface CacheManager {
  /**
   * Exposes the record field keys that have changed. This is collected internally to notify watchers.
   *
   * A special value [ALL_KEYS] indicates that all records have changed.
   *
   * @see publish
   * @see watch
   */
  val changedKeys: SharedFlow<Set<String>>

  companion object {
    val ALL_KEYS = object : AbstractSet<String>() {
      override val size = 0

      override fun iterator() = emptySet<String>().iterator()

      override fun equals(other: Any?) = other === this

      override fun hashCode() = 0
    }
  }

  /**
   * Reads an operation from the store.
   *
   * The returned [ApolloResponse.data] has `null` values for any missing fields if their type is nullable, propagating up to their parent
   * otherwise. Missing fields have a corresponding [Error]
   * in [ApolloResponse.errors].
   *
   * @param operation the operation to read
   */
  suspend fun <D : Operation.Data> readOperation(
      operation: Operation<D>,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): ApolloResponse<D>

  /**
   * Reads a fragment from the store.
   *
   * @param fragment the fragment to read
   * @param cacheKey the root where to read the fragment data from
   *
   * @throws [com.apollographql.apollo.exception.CacheMissException] on cache miss
   * @throws [com.apollographql.apollo.exception.ApolloException] on other cache read errors
   *
   * @return the fragment data with optional headers from the [NormalizedCache]
   */
  suspend fun <D : Fragment.Data> readFragment(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): ReadResult<D>

  /**
   * Writes an operation to the store.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param operation the operation to write
   * @param data the operation data to write
   * @param errors the operation errors to write
   * @return the changed field keys
   *
   * @see publish
   */
  suspend fun <D : Operation.Data> writeOperation(
      operation: Operation<D>,
      data: D,
      errors: List<Error>? = null,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): Set<String>

  /**
   * Writes an operation to the store.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param operation the operation to write
   * @param dataWithErrors the operation data to write as a [DataWithErrors] object
   * @return the changed field keys
   *
   * @see publish
   */
  suspend fun <D : Operation.Data> writeOperation(
      operation: Operation<D>,
      dataWithErrors: DataWithErrors,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): Set<String>

  /**
   * Writes a fragment to the store.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param fragment the fragment to write
   * @param cacheKey the root where to write the fragment data to
   * @param data the fragment data to write
   * @return the changed field keys
   *
   * @see publish
   */
  suspend fun <D : Fragment.Data> writeFragment(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      data: D,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
      cacheHeaders: CacheHeaders = CacheHeaders.NONE,
  ): Set<String>

  /**
   * Writes an operation to the optimistic store.
   *
   * Optimistic updates must be enabled to use this method. To do so, pass `enableOptimisticUpdates = true` to the `CacheManager` constructor
   * or [normalizedCache] extension.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param operation the operation to write
   * @param data the operation data to write
   * @param mutationId a unique identifier for this optimistic update
   * @return the changed field keys
   *
   * @see publish
   */
  suspend fun <D : Operation.Data> writeOptimisticUpdates(
      operation: Operation<D>,
      data: D,
      mutationId: Uuid,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
  ): Set<String>

  /**
   * Writes a fragment to the optimistic store.
   *
   * Optimistic updates must be enabled to use this method. To do so, pass `enableOptimisticUpdates = true` to the `CacheManager` constructor
   * or [normalizedCache] extension.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param fragment the fragment to write
   * @param cacheKey the root where to write the fragment data to
   * @param data the fragment data to write
   * @param mutationId a unique identifier for this optimistic update
   * @return the changed field keys
   *
   * @see publish
   */
  suspend fun <D : Fragment.Data> writeOptimisticUpdates(
      fragment: Fragment<D>,
      cacheKey: CacheKey,
      data: D,
      mutationId: Uuid,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
  ): Set<String>

  /**
   * Rollbacks optimistic updates.
   *
   * Optimistic updates must be enabled to use this method. To do so, pass `enableOptimisticUpdates = true` to the `CacheManager` constructor
   * or [normalizedCache] extension.
   *
   * Call [publish] with the returned keys to notify any watchers.
   *
   * @param mutationId the unique identifier of the optimistic update to rollback
   * @return the changed field keys
   *
   * @see publish
   */
  suspend fun rollbackOptimisticUpdates(
      mutationId: Uuid,
  ): Set<String>

  /**
   * Clears all records.
   *
   * Call [publish] with [ALL_KEYS] to notify any watchers.
   *
   * @return `true` if all records were successfully removed, `false` otherwise
   */
  suspend fun clearAll(): Boolean

  /**
   * Removes a record by its key.
   *
   * Call [publish] with [ALL_KEYS] to notify any watchers.
   *
   * @param cacheKey the key of the record to remove
   * @param cascade whether referenced records should also be removed
   * @return `true` if the record was successfully removed, `false` otherwise
   */
  suspend fun remove(cacheKey: CacheKey, cascade: Boolean = true): Boolean

  /**
   * Removes a list of records by their keys.
   * This is an optimized version of [remove] for caches that can batch operations.
   *
   * Call [publish] with [ALL_KEYS] to notify any watchers.
   *
   * @param cacheKeys the keys of the records to remove
   * @param cascade whether referenced records should also be removed
   * @return the number of records that have been removed
   */
  suspend fun remove(cacheKeys: List<CacheKey>, cascade: Boolean = true): Int

  /**
   * Trims the store if its size exceeds [maxSizeBytes]. The amount of data to remove is determined by [trimFactor].
   * The oldest records are removed according to their updated date.
   *
   * This may not be supported by all cache implementations (currently this is implemented by the SQL cache).
   *
   * @param maxSizeBytes the size of the cache in bytes above which the cache should be trimmed.
   * @param trimFactor the factor of the cache size to trim.
   * @return the cache size in bytes after trimming or -1 if the operation is not supported.
   */
  suspend fun trim(maxSizeBytes: Long, trimFactor: Float = 0.1f): Long

  /**
   * Normalizes executable data to a map of [Record] keyed by [Record.key].
   */
  fun <D : Executable.Data> normalize(
      executable: Executable<D>,
      dataWithErrors: DataWithErrors,
      rootKey: CacheKey = CacheKey.QUERY_ROOT,
      customScalarAdapters: CustomScalarAdapters = CustomScalarAdapters.Empty,
  ): Map<CacheKey, Record>

  /**
   * Publishes a set of keys of record fields that have changed. This will notify watchers and any subscribers of [changedKeys].
   *
   * Pass [ALL_KEYS] to indicate that all records have changed, for instance after a [clearAll] operation.
   *
   * @see changedKeys
   * @see watch
   *
   * @param keys A set of keys of [Record] fields which have changed.
   */
  suspend fun publish(keys: Set<String>)

  /**
   * Direct access to the cache.
   *
   * @param block a function that can access the cache.
   */
  suspend fun <R> accessCache(block: suspend (NormalizedCache) -> R): R

  /**
   * Dumps the content of the store for debugging purposes.
   */
  suspend fun dump(): Map<KClass<*>, Map<CacheKey, Record>>

  /**
   * Releases resources associated with this store.
   */
  fun dispose()

  class ReadResult<D : Executable.Data>(
      val data: D,
      val cacheHeaders: CacheHeaders,
  )
}

fun CacheManager(
    normalizedCacheFactory: NormalizedCacheFactory,
    cacheKeyGenerator: CacheKeyGenerator = @Suppress("DEPRECATION") TypePolicyCacheKeyGenerator,
    metadataGenerator: MetadataGenerator = EmptyMetadataGenerator,
    cacheResolver: CacheResolver = com.apollographql.cache.normalized.api.FieldPolicyCacheResolver(keyScope = CacheKey.Scope.TYPE),
    recordMerger: RecordMerger = DefaultRecordMerger,
    fieldKeyGenerator: FieldKeyGenerator = DefaultFieldKeyGenerator,
    embeddedFieldsProvider: EmbeddedFieldsProvider = DefaultEmbeddedFieldsProvider,
    maxAgeProvider: MaxAgeProvider = DefaultMaxAgeProvider,
    enableOptimisticUpdates: Boolean = false,
): CacheManager = DefaultCacheManager(
    normalizedCacheFactory = normalizedCacheFactory,
    cacheKeyGenerator = cacheKeyGenerator,
    metadataGenerator = metadataGenerator,
    cacheResolver = cacheResolver,
    recordMerger = recordMerger,
    fieldKeyGenerator = fieldKeyGenerator,
    embeddedFieldsProvider = embeddedFieldsProvider,
    maxAgeProvider = maxAgeProvider,
    enableOptimisticUpdates = enableOptimisticUpdates,
)

internal expect fun CacheManager.cacheDumpProvider(): () -> Map<String, Map<String, Pair<Int, Map<String, Any?>>>>

internal expect fun KClass<*>.normalizedCacheName(): String
