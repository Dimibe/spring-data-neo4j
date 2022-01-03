/*
 * Copyright 2011-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.neo4j.core;

import org.neo4j.driver.Bookmark;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.reactive.RxQueryRunner;
import org.neo4j.driver.reactive.RxResult;
import org.neo4j.driver.reactive.RxSession;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.types.TypeSystem;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.ConverterRegistry;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.dao.DataAccessException;
import org.springframework.data.neo4j.core.convert.Neo4jConversions;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionUtils;
import org.springframework.data.neo4j.core.transaction.ReactiveNeo4jTransactionManager;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reactive variant of the {@link Neo4jClient}.
 *
 * @author Michael J. Simons
 * @author Gerrit Meier
 * @soundtrack Die Toten Hosen - Im Auftrag des Herrn
 * @since 6.0
 */
class DefaultReactiveNeo4jClient implements ReactiveNeo4jClient {

	private final Driver driver;
	private final TypeSystem typeSystem;
	private @Nullable final ReactiveDatabaseSelectionProvider databaseSelectionProvider;
	private final ConversionService conversionService;
	private final Neo4jPersistenceExceptionTranslator persistenceExceptionTranslator = new Neo4jPersistenceExceptionTranslator();

	// Basically a local bookmark manager
	private final Set<Bookmark> bookmarks = new HashSet<>();
	private final ReentrantReadWriteLock bookmarksLock = new ReentrantReadWriteLock();

	DefaultReactiveNeo4jClient(Driver driver, @Nullable ReactiveDatabaseSelectionProvider databaseSelectionProvider) {

		this.driver = driver;
		this.typeSystem = driver.defaultTypeSystem();
		this.databaseSelectionProvider = databaseSelectionProvider;
		this.conversionService = new DefaultConversionService();
		new Neo4jConversions().registerConvertersIn((ConverterRegistry) conversionService);
	}

	Mono<RxQueryRunner> retrieveRxStatementRunnerHolder(Mono<DatabaseSelection> databaseSelection) {

		return databaseSelection.flatMap(targetDatabase ->
				ReactiveNeo4jTransactionManager.retrieveReactiveTransaction(driver, targetDatabase.getValue())
						.map(RxQueryRunner.class::cast)
						.zipWith(Mono.just(Collections.<Bookmark>emptySet()))
						.switchIfEmpty(Mono.fromSupplier(() -> {
							ReentrantReadWriteLock.ReadLock lock = bookmarksLock.readLock();
							try {
								lock.lock();
								Set<Bookmark> lastBookmarks = new HashSet<>(bookmarks);
								return Tuples.of(driver.rxSession(Neo4jTransactionUtils.sessionConfig(false, lastBookmarks, targetDatabase.getValue())), lastBookmarks);
							} finally {
								lock.unlock();
							}
						})))
				.map(t -> new DelegatingQueryRunner(t.getT1(), t.getT2(), (usedBookmarks, newBookmark) -> {
					ReentrantReadWriteLock.WriteLock lock = bookmarksLock.writeLock();
					try {
						lock.lock();
						bookmarks.removeAll(usedBookmarks);
						bookmarks.add(newBookmark);
					} finally {
						lock.unlock();
					}
				}));
	}

	private static class DelegatingQueryRunner implements RxQueryRunner {

		private final RxQueryRunner delegate;
		private final Collection<Bookmark> usedBookmarks;
		private final BiConsumer<Collection<Bookmark>, Bookmark> newBookmarkConsumer;

		private DelegatingQueryRunner(RxQueryRunner delegate, Collection<Bookmark> lastBookmarks, BiConsumer<Collection<Bookmark>, Bookmark> newBookmarkConsumer) {
			this.delegate = delegate;
			this.usedBookmarks = lastBookmarks;
			this.newBookmarkConsumer = newBookmarkConsumer;
		}

		Mono<Void> close() {

			// We're only going to close sessions we have acquired inside the client, not something that
			// has been retrieved from the tx manager.
			if (this.delegate instanceof RxSession) {
				RxSession session = (RxSession) this.delegate;
				return Mono.fromDirect(session.close()).then().doOnSuccess(signal ->
						this.newBookmarkConsumer.accept(usedBookmarks, session.lastBookmark()));
			}

			return Mono.empty();
		}

		@Override
		public RxResult run(String query, Value parameters) {
			return delegate.run(query, parameters);
		}

		@Override
		public RxResult run(String query, Map<String, Object> parameters) {
			return delegate.run(query, parameters);
		}

		@Override
		public RxResult run(String query, Record parameters) {
			return delegate.run(query, parameters);
		}

		@Override
		public RxResult run(String query) {
			return delegate.run(query);
		}

		@Override
		public RxResult run(Query query) {
			return delegate.run(query);
		}
	}

	<T> Mono<T> doInQueryRunnerForMono(Mono<DatabaseSelection> databaseSelection, Function<RxQueryRunner, Mono<T>> func) {

		return Mono.usingWhen(retrieveRxStatementRunnerHolder(databaseSelection), func, runner -> ((DelegatingQueryRunner) runner).close());
	}

	<T> Flux<T> doInStatementRunnerForFlux(Mono<DatabaseSelection> databaseSelection, Function<RxQueryRunner, Flux<T>> func) {

		return Flux.usingWhen(retrieveRxStatementRunnerHolder(databaseSelection), func, runner -> ((DelegatingQueryRunner) runner).close());
	}

	@Override
	public RunnableSpec query(String cypher) {
		return query(() -> cypher);
	}

	@Override
	public RunnableSpec query(Supplier<String> cypherSupplier) {
		return new DefaultRunnableSpec(cypherSupplier);
	}

	@Override
	public <T> OngoingDelegation<T> delegateTo(Function<RxQueryRunner, Mono<T>> callback) {
		return new DefaultRunnableDelegation<>(callback);
	}

	@Override
	public ReactiveDatabaseSelectionProvider getDatabaseSelectionProvider() {
		return databaseSelectionProvider;
	}

	private Mono<DatabaseSelection> resolveTargetDatabaseName(@Nullable String parameterTargetDatabase) {

		String value = Neo4jClient.verifyDatabaseName(parameterTargetDatabase);
		if (value != null) {
			return Mono.just(DatabaseSelection.byName(value));
		}
		if (databaseSelectionProvider != null) {
			return databaseSelectionProvider.getDatabaseSelection();
		}
		return ReactiveDatabaseSelectionProvider.getDefaultSelectionProvider().getDatabaseSelection();
	}

	class DefaultRunnableSpec implements RunnableSpec {

		private final Supplier<String> cypherSupplier;

		private Mono<DatabaseSelection> databaseSelection;

		private final NamedParameters parameters = new NamedParameters();

		DefaultRunnableSpec(Supplier<String> cypherSupplier) {
			this.databaseSelection = resolveTargetDatabaseName(null);
			this.cypherSupplier = cypherSupplier;
		}

		@Override
		public RunnableSpecTightToDatabase in(String targetDatabase) {

			this.databaseSelection = resolveTargetDatabaseName(targetDatabase);
			return this;
		}

		class DefaultOngoingBindSpec<T> implements Neo4jClient.OngoingBindSpec<T, RunnableSpecTightToDatabase> {

			@Nullable private final T value;

			DefaultOngoingBindSpec(@Nullable T value) {
				this.value = value;
			}

			@Override
			public RunnableSpecTightToDatabase to(String name) {

				DefaultRunnableSpec.this.parameters.add(name, value);
				return DefaultRunnableSpec.this;
			}

			@Override
			public RunnableSpecTightToDatabase with(Function<T, Map<String, Object>> binder) {

				Assert.notNull(binder, "Binder is required.");

				return bindAll(binder.apply(value));
			}
		}

		@Override
		public Neo4jClient.OngoingBindSpec<?, RunnableSpecTightToDatabase> bind(@Nullable Object value) {
			return new DefaultOngoingBindSpec(value);
		}

		@Override
		public RunnableSpecTightToDatabase bindAll(Map<String, Object> newParameters) {
			this.parameters.addAll(newParameters);
			return this;
		}

		@Override
		public <R> MappingSpec<R> fetchAs(Class<R> targetClass) {

			return new DefaultRecordFetchSpec<>(this.databaseSelection, this.cypherSupplier, this.parameters,
					new SingleValueMappingFunction(conversionService, targetClass));
		}

		@Override
		public RecordFetchSpec<Map<String, Object>> fetch() {

			return new DefaultRecordFetchSpec<>(databaseSelection, cypherSupplier, parameters, (t, r) -> r.asMap());
		}

		@Override
		public Mono<ResultSummary> run() {

			return new DefaultRecordFetchSpec<>(this.databaseSelection, this.cypherSupplier, this.parameters, null).run();
		}
	}

	class DefaultRecordFetchSpec<T> implements RecordFetchSpec<T>, MappingSpec<T> {

		private final Mono<DatabaseSelection> databaseSelection;

		private final Supplier<String> cypherSupplier;

		private final NamedParameters parameters;

		private BiFunction<TypeSystem, Record, T> mappingFunction;

		DefaultRecordFetchSpec(Mono<DatabaseSelection> databaseSelectionMono, Supplier<String> cypherSupplier, NamedParameters parameters,
				@Nullable BiFunction<TypeSystem, Record, T> mappingFunction) {

			this.databaseSelection = databaseSelectionMono;
			this.cypherSupplier = cypherSupplier;
			this.parameters = parameters;
			this.mappingFunction = mappingFunction;
		}

		@Override
		public RecordFetchSpec<T> mappedBy(@SuppressWarnings("HiddenField") BiFunction<TypeSystem, Record, T> mappingFunction) {

			this.mappingFunction = new DelegatingMappingFunctionWithNullCheck<>(mappingFunction);
			return this;
		}

		Mono<Tuple2<String, Map<String, Object>>> prepareStatement() {
			if (cypherLog.isDebugEnabled()) {
				String cypher = cypherSupplier.get();
				cypherLog.debug(() -> String.format("Executing:%s%s", System.lineSeparator(), cypher));

				if (cypherLog.isTraceEnabled() && !parameters.isEmpty()) {
					cypherLog.trace(() -> String.format("with parameters:%s%s", System.lineSeparator(), parameters));
				}
			}
			return Mono.fromSupplier(cypherSupplier).zipWith(Mono.just(parameters.get()));
		}

		Flux<T> executeWith(Tuple2<String, Map<String, Object>> t, RxQueryRunner runner) {

			return Flux.usingWhen(Flux.just(runner.run(t.getT1(), t.getT2())),
					result -> Flux.from(result.records()).mapNotNull(r -> mappingFunction.apply(typeSystem, r)),
					result -> Flux.from(result.consume()).doOnNext(ResultSummaries::process));
		}

		@Override
		public Mono<T> one() {

			return doInQueryRunnerForMono(databaseSelection,
					(runner) -> prepareStatement().flatMapMany(t -> executeWith(t, runner)).singleOrEmpty())
							.onErrorMap(RuntimeException.class, DefaultReactiveNeo4jClient.this::potentiallyConvertRuntimeException);
		}

		@Override
		public Mono<T> first() {

			return doInQueryRunnerForMono(databaseSelection,
					runner -> prepareStatement().flatMapMany(t -> executeWith(t, runner)).next())
							.onErrorMap(RuntimeException.class, DefaultReactiveNeo4jClient.this::potentiallyConvertRuntimeException);
		}

		@Override
		public Flux<T> all() {

			return doInStatementRunnerForFlux(databaseSelection,
					runner -> prepareStatement().flatMapMany(t -> executeWith(t, runner)))
					.onErrorMap(RuntimeException.class, DefaultReactiveNeo4jClient.this::potentiallyConvertRuntimeException);
		}

		Mono<ResultSummary> run() {

			return doInQueryRunnerForMono(databaseSelection, runner -> prepareStatement().flatMap(t -> {
				RxResult rxResult = runner.run(t.getT1(), t.getT2());
				return Flux.from(rxResult.records()).then(Mono.from(rxResult.consume()).map(ResultSummaries::process));
			})).onErrorMap(RuntimeException.class, DefaultReactiveNeo4jClient.this::potentiallyConvertRuntimeException);
		}
	}

	/**
	 * Tries to convert the given {@link RuntimeException} into a {@link DataAccessException} but returns the original
	 * exception if the conversation failed. Thus allows safe re-throwing of the return value.
	 *
	 * @param ex the exception to translate
	 * @return
	 */
	private RuntimeException potentiallyConvertRuntimeException(RuntimeException ex) {
		RuntimeException resolved = persistenceExceptionTranslator.translateExceptionIfPossible(ex);
		return resolved == null ? ex : resolved;
	}

	class DefaultRunnableDelegation<T> implements RunnableDelegation<T>, OngoingDelegation<T> {

		private final Function<RxQueryRunner, Mono<T>> callback;

		private Mono<DatabaseSelection> databaseSelection;

		DefaultRunnableDelegation(Function<RxQueryRunner, Mono<T>> callback) {
			this.callback = callback;
			this.databaseSelection = resolveTargetDatabaseName(null);
		}

		@Override
		public RunnableDelegation in(@Nullable @SuppressWarnings("HiddenField") String targetDatabase) {

			this.databaseSelection = resolveTargetDatabaseName(targetDatabase);
			return this;
		}

		@Override
		public Mono<T> run() {

			return doInQueryRunnerForMono(databaseSelection, callback);
		}
	}
}
