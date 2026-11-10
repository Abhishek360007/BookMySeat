package com.abhishekraj.BookMySeat.config;

import com.abhishekraj.BookMySeat.dto.FilmCardResponse;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.ShowResponse;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

// Read caches in Redis, so every app instance shares them and an admin edit clears them everywhere.
// HIGHEST_PRECEDENCE: the cache is checked before @Transactional starts, so a hit never touches the database.
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig implements CachingConfigurer {

	public static final String SHOW = "show";
	public static final String SHOW_SEARCH = "showSearch";
	public static final String FILM_SEARCH = "filmSearch";

	// A cache only makes reads faster; if Redis is slow or down, log it and read from the database
	// instead of failing the request (the default handler would turn it into a 500)
	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler(false);
	}

	@Bean
	public RedisCacheManagerBuilderCustomizer redisCaches(RedisConnectionFactory connectionFactory, JsonMapper jsonMapper) {
		JavaType showType = jsonMapper.constructType(ShowResponse.class);
		JavaType searchType = jsonMapper.getTypeFactory().constructParametricType(PageResponse.class, ShowResponse.class);
		JavaType filmSearchType = jsonMapper.getTypeFactory().constructParametricType(PageResponse.class, FilmCardResponse.class);

		return builder -> builder
				// SCAN instead of KEYS when clearing a whole cache (KEYS blocks Redis on a big keyspace).
				// immediateWrites: with Lettuce, evictions otherwise run in the background, so a read right
				// after an admin edit could still get the old entry.
				.cacheWriter(RedisCacheWriter.create(connectionFactory,
						writer -> writer.batchStrategy(BatchStrategies.scan(100)).immediateWrites()))
				.withCacheConfiguration(SHOW, cache(jsonMapper, showType, Duration.ofMinutes(10)))
				// Short TTL: by default search means "upcoming from now", and shows drop out as they start
				.withCacheConfiguration(SHOW_SEARCH, cache(jsonMapper, searchType, Duration.ofSeconds(60)))
				// The home page: films now showing, same short TTL for the same reason
				.withCacheConfiguration(FILM_SEARCH, cache(jsonMapper, filmSearchType, Duration.ofSeconds(60)));
	}

	// Typed JSON (no class names stored in Redis), so cached data can only ever turn back into the expected DTO
	private static RedisCacheConfiguration cache(JsonMapper mapper, JavaType type, Duration ttl) {
		return RedisCacheConfiguration.defaultCacheConfig()
				.entryTtl(ttl)
				.disableCachingNullValues()
				.prefixCacheNameWith("cache:")
				.serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(mapper, type)));
	}
}
