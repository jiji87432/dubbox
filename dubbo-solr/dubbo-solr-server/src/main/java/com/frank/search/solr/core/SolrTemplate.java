/*
 * Copyright 2012 - 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frank.search.solr.core;

import com.frank.search.solr.SolrRealtimeGetRequest;
import com.frank.search.solr.UncategorizedSolrException;
import com.frank.search.solr.VersionUtil;
import com.frank.search.solr.core.convert.SolrConverter;
import com.frank.search.solr.core.mapping.SimpleSolrMappingContext;
import com.frank.search.solr.core.mapping.SolrPersistentEntity;
import com.frank.search.solr.core.query.*;
import com.frank.search.solr.core.query.result.*;
import com.frank.search.solr.core.schema.SolrPersistentEntitySchemaCreator;
import com.frank.search.solr.core.schema.SolrSchemaRequest;
import com.frank.search.solr.core.convert.MappingSolrConverter;
import com.frank.search.solr.core.mapping.SolrPersistentProperty;
import com.frank.search.solr.core.schema.SolrJsonResponse;
import com.frank.search.solr.server.SolrClientFactory;
import com.frank.search.solr.server.support.HttpSolrClientFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

/**
 * Implementation of {@link SolrOperations}
 * 
 * @author Christoph Strobl
 * @author Joachim Uhrlass
 * @author Francisco Spaeth
 */
public class SolrTemplate implements SolrOperations, InitializingBean, ApplicationContextAware {

	private static final Logger LOGGER = LoggerFactory.getLogger(SolrTemplate.class);
	private static final PersistenceExceptionTranslator EXCEPTION_TRANSLATOR = new SolrExceptionTranslator();
	private final QueryParsers queryParsers = new QueryParsers();
	private MappingContext<? extends SolrPersistentEntity<?>, SolrPersistentProperty> mappingContext;

	private ApplicationContext applicationContext;
	private String solrCore;

	@SuppressWarnings("serial")
	private static final List<String> ITERABLE_CLASSES = new ArrayList<String>() {
		{
			add(List.class.getName());
			add(Collection.class.getName());
			add(Iterator.class.getName());
		}
	};

	private SolrClientFactory solrClientFactory;

	private SolrConverter solrConverter;

	private Set<SolrPersistentEntitySchemaCreator.Feature> schemaCreationFeatures;

	public SolrTemplate(SolrClient solrClient) {
		this(solrClient, null);
	}

	public SolrTemplate(SolrClient solrClient, String core) {
		this(new HttpSolrClientFactory(solrClient, core));
		this.solrCore = core;
	}

	public SolrTemplate(SolrClientFactory solrClientFactory) {
		this(solrClientFactory, null);
	}

	public SolrTemplate(SolrClientFactory solrClientFactory, SolrConverter solrConverter) {
		Assert.notNull(solrClientFactory, "SolrClientFactory must not be 'null'.");
		Assert.notNull(solrClientFactory.getSolrClient(), "SolrClientFactory has to return a SolrClient.");

		this.solrClientFactory = solrClientFactory;
	}

	@Override
	public <T> T execute(SolrCallback<T> action) {
		Assert.notNull(action);

		try {
			SolrClient solrClient = this.getSolrClient();
			return action.doInSolr(solrClient);
		} catch (Exception e) {
			DataAccessException resolved = getExceptionTranslator()
					.translateExceptionIfPossible(new RuntimeException(e.getMessage(), e));
			throw resolved == null ? new UncategorizedSolrException(e.getMessage(), e) : resolved;
		}
	}

	@Override
	public SolrPingResponse ping() {
		return execute(new SolrCallback<SolrPingResponse>() {
			@Override
			public SolrPingResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.ping();
			}
		});
	}

	@Override
	public long count(final SolrDataQuery query) {
		Assert.notNull(query, "Query must not be 'null'.");

		return execute(new SolrCallback<Long>() {

			@Override
			public Long doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				SolrQuery solrQuery = queryParsers.getForClass(query.getClass()).constructSolrQuery(query);
				solrQuery.setStart(0);
				solrQuery.setRows(0);

				return solrClient.query(solrQuery).getResults().getNumFound();
			}
		});
	}

	@Override
	public UpdateResponse saveBean(Object obj) {
		return saveBean(obj, -1);
	}

	@Override
	public UpdateResponse saveBean(final Object objectToAdd, final int commitWithinMs) {
		assertNoCollection(objectToAdd);
		return execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.add(convertBeanToSolrInputDocument(objectToAdd), commitWithinMs);
			}
		});
	}

	@Override
	public UpdateResponse saveBeans(Collection<?> beans) {
		return saveBeans(beans, -1);
	}

	@Override
	public UpdateResponse saveBeans(final Collection<?> beansToAdd, final int commitWithinMs) {
		return execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.add(convertBeansToSolrInputDocuments(beansToAdd), commitWithinMs);
			}
		});
	}

	@Override
	public UpdateResponse saveDocument(SolrInputDocument document) {
		return saveDocument(document, -1);
	}

	@Override
	public UpdateResponse saveDocument(final SolrInputDocument documentToAdd, final int commitWithinMs) {
		return execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.add(documentToAdd, commitWithinMs);
			}
		});
	}

	@Override
	public UpdateResponse saveDocuments(Collection<SolrInputDocument> documents) {
		return saveDocuments(documents, -1);
	}

	@Override
	public UpdateResponse saveDocuments(final Collection<SolrInputDocument> documentsToAdd, final int commitWithinMs) {
		return execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.add(documentsToAdd, commitWithinMs);
			}
		});
	}

	@Override
	public UpdateResponse delete(SolrDataQuery query) {
		Assert.notNull(query, "Query must not be 'null'.");

		final String queryString = this.queryParsers.getForClass(query.getClass()).getQueryString(query);

		return execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.deleteByQuery(queryString);
			}
		});
	}

	@Override
	public UpdateResponse deleteById(final String id) {
		Assert.notNull(id, "Cannot delete 'null' id.");

		return execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.deleteById(id);
			}
		});
	}

	@Override
	public UpdateResponse deleteById(Collection<String> ids) {
		Assert.notNull(ids, "Cannot delete 'null' collection.");

		final List<String> toBeDeleted = new ArrayList<String>(ids);
		return execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.deleteById(toBeDeleted);
			}
		});
	}

	/**
	 * 用户自定义的查询
	 * 
	 * @param solrQuery
	 * @return
	 */
	@Override
	public QueryResponse querySolrByCustomDefine(SolrParams solrParams) {
		return executeSolrQuery(solrParams);
	}

	@Override
	public <T> T queryForObject(Query query, Class<T> clazz) {
		Assert.notNull(query, "Query must not be 'null'.");
		Assert.notNull(clazz, "Target class must not be 'null'.");

		query.setPageRequest(new PageRequest(0, 1));
		QueryResponse response = query(query, clazz);

		if (response.getResults().size() > 0) {
			if (response.getResults().size() > 1) {
				LOGGER.warn("More than 1 result found for singe result query ('{}'), returning first entry in list");
			}
			return (T) convertSolrDocumentListToBeans(response.getResults(), clazz).get(0);
		}
		return null;
	}

	private <T> SolrResultPage<T> doQueryForPage(Query query, Class<T> clazz) {

		QueryParserBase.NamedObjectsQuery namedObjectsQuery = new QueryParserBase.NamedObjectsQuery(query);
		QueryResponse response = query(namedObjectsQuery, clazz);

		Map<String, Object> objectsName = namedObjectsQuery.getNamesAssociation();

		return createSolrResultPage(query, clazz, response, objectsName);
	}

	@Override
	public <T> ScoredPage<T> queryForPage(Query query, Class<T> clazz) {
		Assert.notNull(query, "Query must not be 'null'.");
		Assert.notNull(clazz, "Target class must not be 'null'.");

		return doQueryForPage(query, clazz);
	}

	@Override
	public <T> GroupPage<T> queryForGroupPage(Query query, Class<T> clazz) {
		Assert.notNull(query, "Query must not be 'null'.");
		Assert.notNull(clazz, "Target class must not be 'null'.");

		return doQueryForPage(query, clazz);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.solr.core.SolrOperations#queryForStatsPage(org.
	 * springframework.data.solr.core.query.Query, java.lang.Class)
	 */
	@Override
	public <T> StatsPage<T> queryForStatsPage(Query query, Class<T> clazz) {
		Assert.notNull(query, "Query must not be 'null'.");
		Assert.notNull(clazz, "Target class must not be 'null'.");

		return doQueryForPage(query, clazz);
	}

	@Override
	public <T> FacetPage<T> queryForFacetPage(FacetQuery query, Class<T> clazz) {
		Assert.notNull(query, "Query must not be 'null'.");
		Assert.notNull(clazz, "Target class must not be 'null'.");

		QueryParserBase.NamedObjectsFacetQuery namedObjectsQuery = new QueryParserBase.NamedObjectsFacetQuery(query);
		QueryResponse response = query(namedObjectsQuery, clazz);
		Map<String, Object> objectsName = namedObjectsQuery.getNamesAssociation();

		SolrResultPage<T> page = createSolrResultPage(query, clazz, response, objectsName);

		page.addAllFacetFieldResultPages(ResultHelper.convertFacetQueryResponseToFacetPageMap(query, response));
		page.addAllFacetPivotFieldResult(ResultHelper.convertFacetQueryResponseToFacetPivotMap(query, response));
		page.addAllRangeFacetFieldResultPages(
				ResultHelper.convertFacetQueryResponseToRangeFacetPageMap(query, response));
		page.setFacetQueryResultPage(ResultHelper.convertFacetQueryResponseToFacetQueryResult(query, response));

		return page;
	}

	@Override
	public <T> HighlightPage<T> queryForHighlightPage(HighlightQuery query, Class<T> clazz) {
		Assert.notNull(query, "Query must not be 'null'.");
		Assert.notNull(clazz, "Target class must not be 'null'.");

		QueryParserBase.NamedObjectsHighlightQuery namedObjectsQuery = new QueryParserBase.NamedObjectsHighlightQuery(
				query);
		QueryResponse response = query(namedObjectsQuery, clazz);

		Map<String, Object> objectsName = namedObjectsQuery.getNamesAssociation();

		SolrResultPage<T> page = createSolrResultPage(query, clazz, response, objectsName);

		ResultHelper.convertAndAddHighlightQueryResponseToResultPage(response, page);

		return page;
	}

	private <T> SolrResultPage<T> createSolrResultPage(Query query, Class<T> clazz, QueryResponse response,
			Map<String, Object> objectsName) {
		List<T> beans = convertQueryResponseToBeans(response, clazz);
		SolrDocumentList results = response.getResults();
		long numFound = results == null ? 0 : results.getNumFound();
		Float maxScore = results == null ? null : results.getMaxScore();

		Pageable pageRequest = query.getPageRequest();

		SolrResultPage<T> page = new SolrResultPage<T>(beans, pageRequest, numFound, maxScore);

		page.setFieldStatsResults(
				ResultHelper.convertFieldStatsInfoToFieldStatsResultMap(response.getFieldStatsInfo()));
		page.setGroupResults(
				ResultHelper.convertGroupQueryResponseToGroupResultMap(query, objectsName, response, this, clazz));

		return page;
	}

	@Override
	public TermsPage queryForTermsPage(TermsQuery query) {
		Assert.notNull(query, "Query must not be 'null'.");

		QueryResponse response = query(query, null);

		TermsResultPage page = new TermsResultPage();
		page.addAllTerms(ResultHelper.convertTermsQueryResponseToTermsMap(response));
		return page;
	}

	final QueryResponse query(SolrDataQuery query, Class<?> clazz) {
		Assert.notNull(query, "Query must not be 'null'");

		SolrQuery solrQuery = queryParsers.getForClass(query.getClass()).constructSolrQuery(query);

		if (clazz != null) {
			SolrPersistentEntity<?> persistedEntity = mappingContext.getPersistentEntity(clazz);
			if (persistedEntity.hasScoreProperty()) {
				solrQuery.setIncludeScore(true);
			}
		}

		LOGGER.debug("Executing query '" + solrQuery + "' against solr.");

		return executeSolrQuery(solrQuery);
	}

	final QueryResponse executeSolrQuery(final SolrParams solrParams) {
		return execute(new SolrCallback<QueryResponse>() {
			@Override
			public QueryResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.query(solrParams);
			}
		});
	}

	@Override
	public void commit() {
		execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.commit();
			}
		});
	}

	@Override
	public void softCommit() {
		if (VersionUtil.isSolr3XAvailable()) {
			throw new UnsupportedOperationException(
					"Soft commit is not available for solr version lower than 4.x - Please check your depdendencies.");
		}
		execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.commit(true, true, true);
			}
		});
	}

	@Override
	public void rollback() {
		execute(new SolrCallback<UpdateResponse>() {
			@Override
			public UpdateResponse doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				return solrClient.rollback();
			}
		});
	}

	@Override
	public SolrInputDocument convertBeanToSolrInputDocument(Object bean) {
		if (bean instanceof SolrInputDocument) {
			return (SolrInputDocument) bean;
		}

		SolrInputDocument document = new SolrInputDocument();
		getConverter().write(bean, document);
		return document;
	}

	/**
	 * @param collectionName
	 * @return
	 * @since 1.3
	 */
	public String getSchemaName(String collectionName) {
		return execute(new SolrCallback<String>() {

			@Override
			public String doInSolr(SolrClient solrClient) throws SolrServerException, IOException {
				SolrJsonResponse response = SolrSchemaRequest.name().process(solrClient);
				if (response != null) {
					return response.getNode("name").asText();
				}
				return null;
			}
		});
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.springframework.data.solr.core.SolrOperations#queryForCursor(org.
	 * springframework.data.solr.core.query.Query, java.lang.Class)
	 */
	public <T> Cursor<T> queryForCursor(Query query, final Class<T> clazz) {

		return new DelegatingCursor<T>(queryParsers.getForClass(query.getClass()).constructSolrQuery(query)) {

			@Override
			protected PartialResult<T> doLoad(SolrQuery nativeQuery) {

				QueryResponse response = executeSolrQuery(nativeQuery);
				if (response == null) {
					return new PartialResult<T>("", Collections.<T> emptyList());
				}

				return new PartialResult<T>(response.getNextCursorMark(), convertQueryResponseToBeans(response, clazz));
			}

		}.open();
	}

	@Override
	public <T> Collection<T> getById(final Collection<? extends Serializable> ids, final Class<T> clazz) {

		if (CollectionUtils.isEmpty(ids)) {
			return Collections.emptyList();
		}

		return execute(new SolrCallback<Collection<T>>() {
			@Override
			public Collection<T> doInSolr(SolrClient solrClient) throws SolrServerException, IOException {

				QueryResponse response = new SolrRealtimeGetRequest(ids).process(solrClient);
				return convertSolrDocumentListToBeans(response.getResults(), clazz);
			}

		});
	}

	@Override
	public <T> T getById(Serializable id, Class<T> clazz) {

		Assert.notNull(id, "Id must not be 'null'.");

		Collection<T> result = getById(Collections.singletonList(id), clazz);
		if (result.isEmpty()) {
			return null;
		}
		return result.iterator().next();
	}

	private Collection<SolrInputDocument> convertBeansToSolrInputDocuments(Iterable<?> beans) {
		if (beans == null) {
			return Collections.emptyList();
		}

		List<SolrInputDocument> resultList = new ArrayList<SolrInputDocument>();
		for (Object bean : beans) {
			resultList.add(convertBeanToSolrInputDocument(bean));
		}
		return resultList;
	}

	public <T> List<T> convertQueryResponseToBeans(QueryResponse response, Class<T> targetClass) {
		return response != null ? convertSolrDocumentListToBeans(response.getResults(), targetClass)
				: Collections.<T> emptyList();
	}

	public <T> List<T> convertSolrDocumentListToBeans(SolrDocumentList documents, Class<T> targetClass) {
		if (documents == null) {
			return Collections.<T> emptyList();
		}
		return getConverter().read(documents, targetClass);
	}

	public <T> T convertSolrDocumentToBean(SolrDocument document, Class<T> targetClass) {
		return getConverter().read(targetClass, document);
	}

	protected void assertNoCollection(Object o) {
		if (null != o && (o.getClass().isArray() || ITERABLE_CLASSES.contains(o.getClass().getName()))) {
			throw new IllegalArgumentException("Collections are not supported for this operation");
		}
	}

	private final SolrConverter getDefaultSolrConverter() {
		MappingSolrConverter converter = new MappingSolrConverter(this.mappingContext);
		converter.afterPropertiesSet(); // have to call this one to initialize
										// default converters
		return converter;
	}

	@Override
	public final SolrClient getSolrClient() {
		return solrClientFactory.getSolrClient(this.solrCore);
	}

	@Override
	public SolrConverter getConverter() {
		return this.solrConverter;
	}

	public static PersistenceExceptionTranslator getExceptionTranslator() {
		return EXCEPTION_TRANSLATOR;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	public void registerQueryParser(Class<? extends SolrDataQuery> clazz, QueryParser queryParser) {
		this.queryParsers.registerParser(clazz, queryParser);
	}

	public void setSolrConverter(SolrConverter solrConverter) {
		this.solrConverter = solrConverter;
	}

	public String getSolrCore() {
		return solrCore;
	}

	public void setSolrCore(String solrCore) {
		this.solrCore = solrCore;
	}

	@Override
	public void afterPropertiesSet() {

		if (this.mappingContext == null) {
			this.mappingContext = new SimpleSolrMappingContext(
					new SolrPersistentEntitySchemaCreator(this.solrClientFactory).enable(this.schemaCreationFeatures));
		}

		if (this.solrConverter == null) {
			this.solrConverter = getDefaultSolrConverter();
		}
		registerPersistenceExceptionTranslator();
	}

	private void registerPersistenceExceptionTranslator() {
		if (this.applicationContext != null
				&& this.applicationContext.getBeansOfType(PersistenceExceptionTranslator.class).isEmpty()) {
			if (this.applicationContext instanceof ConfigurableApplicationContext) {
				((ConfigurableApplicationContext) this.applicationContext).getBeanFactory()
						.registerSingleton("solrExceptionTranslator", EXCEPTION_TRANSLATOR);
			}
		}
	}

	/**
	 * @since 1.3
	 * @param mappingContext
	 */
	public void setMappingContext(
			MappingContext<? extends SolrPersistentEntity<?>, SolrPersistentProperty> mappingContext) {
		this.mappingContext = mappingContext;
	}

	/**
	 * @since 1.3
	 * @param schemaCreationFeatures
	 */
	public void setSchemaCreationFeatures(
			Collection<SolrPersistentEntitySchemaCreator.Feature> schemaCreationFeatures) {
		this.schemaCreationFeatures = new HashSet<SolrPersistentEntitySchemaCreator.Feature>(schemaCreationFeatures);
	}

	/**
	 * @since 1.3
	 * @return
	 */
	public Set<SolrPersistentEntitySchemaCreator.Feature> getSchemaCreationFeatures() {

		if (CollectionUtils.isEmpty(this.schemaCreationFeatures)) {
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(this.schemaCreationFeatures);
	}

}
