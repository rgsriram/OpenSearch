/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.query;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.opensearch.common.util.io.IOUtils;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParseException;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.search.approximate.ApproximateMatchAllQuery;
import org.opensearch.search.approximate.ApproximateScoreQuery;
import org.opensearch.search.internal.ContextIndexSearcher;
import org.opensearch.search.internal.SearchContext;
import org.opensearch.test.AbstractQueryTestCase;
import org.hamcrest.Matchers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.opensearch.index.query.QueryBuilders.boolQuery;
import static org.opensearch.index.query.QueryBuilders.termQuery;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BoolQueryBuilderTests extends AbstractQueryTestCase<BoolQueryBuilder> {
    @Override
    protected BoolQueryBuilder doCreateTestQueryBuilder() {
        BoolQueryBuilder query = new BoolQueryBuilder();
        if (randomBoolean()) {
            query.adjustPureNegative(randomBoolean());
        }
        if (randomBoolean()) {
            query.minimumShouldMatch(randomMinimumShouldMatch());
        }
        int mustClauses = randomIntBetween(0, 3);
        for (int i = 0; i < mustClauses; i++) {
            query.must(RandomQueryBuilder.createQuery(random()));
        }
        int mustNotClauses = randomIntBetween(0, 3);
        for (int i = 0; i < mustNotClauses; i++) {
            query.mustNot(RandomQueryBuilder.createQuery(random()));
        }
        int shouldClauses = randomIntBetween(0, 3);
        for (int i = 0; i < shouldClauses; i++) {
            query.should(RandomQueryBuilder.createQuery(random()));
        }
        int filterClauses = randomIntBetween(0, 3);
        for (int i = 0; i < filterClauses; i++) {
            query.filter(RandomQueryBuilder.createQuery(random()));
        }
        return query;
    }

    @Override
    protected void doAssertLuceneQuery(BoolQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        if (!queryBuilder.hasClauses()) {
            assertThat(query, instanceOf(ApproximateScoreQuery.class));
            assertThat(((ApproximateScoreQuery) query).getOriginalQuery(), instanceOf(MatchAllDocsQuery.class));
        } else {
            List<BooleanClause> clauses = new ArrayList<>();
            clauses.addAll(getBooleanClauses(queryBuilder.must(), BooleanClause.Occur.MUST, context));
            clauses.addAll(getBooleanClauses(queryBuilder.mustNot(), BooleanClause.Occur.MUST_NOT, context));
            clauses.addAll(getBooleanClauses(queryBuilder.should(), BooleanClause.Occur.SHOULD, context));
            clauses.addAll(getBooleanClauses(queryBuilder.filter(), BooleanClause.Occur.FILTER, context));

            if (clauses.isEmpty()) {
                assertThat(query, instanceOf(ApproximateScoreQuery.class));
                assertThat(((ApproximateScoreQuery) query).getOriginalQuery(), instanceOf(MatchAllDocsQuery.class));
            } else if (query instanceof MatchNoDocsQuery == false) {
                assertThat(query, instanceOf(BooleanQuery.class));
                BooleanQuery booleanQuery = (BooleanQuery) query;
                if (queryBuilder.adjustPureNegative()) {
                    boolean isNegative = true;
                    for (BooleanClause clause : clauses) {
                        if (clause.isProhibited() == false) {
                            isNegative = false;
                            break;
                        }
                    }
                    if (isNegative) {
                        clauses.add(new BooleanClause(new MatchAllDocsQuery(), BooleanClause.Occur.MUST));
                    }
                }
                assertThat(booleanQuery.clauses().size(), equalTo(clauses.size()));
                Iterator<BooleanClause> clauseIterator = clauses.iterator();
                for (BooleanClause booleanClause : booleanQuery.clauses()) {
                    assertThat(booleanClause, instanceOf(clauseIterator.next().getClass()));
                }
            }
        }
    }

    private static List<BooleanClause> getBooleanClauses(
        List<QueryBuilder> queryBuilders,
        BooleanClause.Occur occur,
        QueryShardContext context
    ) throws IOException {
        List<BooleanClause> clauses = new ArrayList<>();
        for (QueryBuilder query : queryBuilders) {
            Query innerQuery = query.rewrite(context).toQuery(context);
            if (innerQuery != null) {
                clauses.add(new BooleanClause(innerQuery, occur));
            }
        }
        return clauses;
    }

    @Override
    protected Map<String, BoolQueryBuilder> getAlternateVersions() {
        Map<String, BoolQueryBuilder> alternateVersions = new HashMap<>();
        BoolQueryBuilder tempQueryBuilder = createTestQueryBuilder();
        BoolQueryBuilder expectedQuery = new BoolQueryBuilder();
        String contentString = "{\n" + "    \"bool\" : {\n";
        if (tempQueryBuilder.must().size() > 0) {
            QueryBuilder must = tempQueryBuilder.must().get(0);
            contentString += "\"must\": " + must.toString() + ",";
            expectedQuery.must(must);
        }
        if (tempQueryBuilder.mustNot().size() > 0) {
            QueryBuilder mustNot = tempQueryBuilder.mustNot().get(0);
            contentString += "\"must_not\":" + mustNot.toString() + ",";
            expectedQuery.mustNot(mustNot);
        }
        if (tempQueryBuilder.should().size() > 0) {
            QueryBuilder should = tempQueryBuilder.should().get(0);
            contentString += "\"should\": " + should.toString() + ",";
            expectedQuery.should(should);
        }
        if (tempQueryBuilder.filter().size() > 0) {
            QueryBuilder filter = tempQueryBuilder.filter().get(0);
            contentString += "\"filter\": " + filter.toString() + ",";
            expectedQuery.filter(filter);
        }
        contentString = contentString.substring(0, contentString.length() - 1);
        contentString += "    }    \n" + "}";
        alternateVersions.put(contentString, expectedQuery);
        return alternateVersions;
    }

    public void testIllegalArguments() {
        BoolQueryBuilder booleanQuery = new BoolQueryBuilder();
        expectThrows(IllegalArgumentException.class, () -> booleanQuery.must(null));
        expectThrows(IllegalArgumentException.class, () -> booleanQuery.mustNot(null));
        expectThrows(IllegalArgumentException.class, () -> booleanQuery.should(null));
    }

    // https://github.com/elastic/elasticsearch/issues/7240
    public void testEmptyBooleanQuery() throws Exception {
        XContentBuilder contentBuilder = MediaTypeRegistry.contentBuilder(randomFrom(XContentType.values()));
        contentBuilder.startObject().startObject("bool").endObject().endObject();
        try (XContentParser xParser = createParser(contentBuilder)) {
            Query parsedQuery = parseQuery(xParser).toQuery(createShardContext());
            assertThat(parsedQuery, Matchers.instanceOf(MatchAllDocsQuery.class));
        }
    }

    public void testMinShouldMatchFilterWithoutShouldClauses() throws Exception {
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.filter(new BoolQueryBuilder().must(new MatchAllQueryBuilder()));
        Query query = boolQueryBuilder.toQuery(createShardContext());
        assertThat(query, instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) query;
        assertThat(booleanQuery.getMinimumNumberShouldMatch(), equalTo(0));
        assertThat(booleanQuery.clauses().size(), equalTo(1));
        BooleanClause booleanClause = booleanQuery.clauses().get(0);
        assertThat(booleanClause.occur(), equalTo(BooleanClause.Occur.FILTER));
        assertThat(booleanClause.query(), instanceOf(BooleanQuery.class));
        BooleanQuery innerBooleanQuery = (BooleanQuery) booleanClause.query();
        // we didn't set minimum should match initially, there are no should clauses so it should be 0
        assertThat(innerBooleanQuery.getMinimumNumberShouldMatch(), equalTo(0));
        assertThat(innerBooleanQuery.clauses().size(), equalTo(1));
        BooleanClause innerBooleanClause = innerBooleanQuery.clauses().get(0);
        assertThat(innerBooleanClause.occur(), equalTo(BooleanClause.Occur.MUST));
        assertThat(innerBooleanClause.query(), instanceOf(ApproximateScoreQuery.class));
        ApproximateScoreQuery approxQuery = (ApproximateScoreQuery) innerBooleanClause.query();
        assertThat(approxQuery.getOriginalQuery(), instanceOf(MatchAllDocsQuery.class));
        assertThat(approxQuery.getApproximationQuery(), instanceOf(ApproximateMatchAllQuery.class));
    }

    public void testMinShouldMatchBiggerThanNumberOfShouldClauses() throws Exception {
        BooleanQuery bq = (BooleanQuery) parseQuery(
            boolQuery().should(termQuery(TEXT_FIELD_NAME, "bar")).should(termQuery(KEYWORD_FIELD_NAME, "bar2")).minimumShouldMatch("3")
        ).toQuery(createShardContext());
        assertEquals(3, bq.getMinimumNumberShouldMatch());

        bq = (BooleanQuery) parseQuery(
            boolQuery().should(termQuery(TEXT_FIELD_NAME, "bar")).should(termQuery(KEYWORD_FIELD_NAME, "bar2")).minimumShouldMatch(3)
        ).toQuery(createShardContext());
        assertEquals(3, bq.getMinimumNumberShouldMatch());
    }

    public void testMinShouldMatchDisableCoord() throws Exception {
        BooleanQuery bq = (BooleanQuery) parseQuery(
            boolQuery().should(termQuery(TEXT_FIELD_NAME, "bar")).should(termQuery(TEXT_FIELD_NAME, "bar2")).minimumShouldMatch("3")
        ).toQuery(createShardContext());
        assertEquals(3, bq.getMinimumNumberShouldMatch());
    }

    public void testFromJson() throws IOException {
        String query = "{"
            + "\"bool\" : {"
            + "  \"must\" : [ {"
            + "    \"term\" : {"
            + "      \"user\" : {"
            + "        \"value\" : \"foobar\","
            + "        \"boost\" : 1.0"
            + "      }"
            + "    }"
            + "  } ],"
            + "  \"filter\" : [ {"
            + "    \"term\" : {"
            + "      \"tag\" : {"
            + "        \"value\" : \"tech\","
            + "        \"boost\" : 1.0"
            + "      }"
            + "    }"
            + "  } ],"
            + "  \"must_not\" : [ {"
            + "    \"range\" : {"
            + "      \"age\" : {"
            + "        \"from\" : 10,"
            + "        \"to\" : 20,"
            + "        \"include_lower\" : true,"
            + "        \"include_upper\" : true,"
            + "        \"boost\" : 1.0"
            + "      }"
            + "    }"
            + "  } ],"
            + "  \"should\" : [ {"
            + "    \"term\" : {"
            + "      \"tag\" : {"
            + "        \"value\" : \"wow\","
            + "        \"boost\" : 1.0"
            + "      }"
            + "    }"
            + "  }, {"
            + "    \"term\" : {"
            + "      \"tag\" : {"
            + "        \"value\" : \"opensearch\","
            + "        \"boost\" : 1.0"
            + "      }"
            + "    }"
            + "  } ],"
            + "  \"adjust_pure_negative\" : true,"
            + "  \"minimum_should_match\" : \"23\","
            + "  \"boost\" : 42.0"
            + "}"
            + "}";

        BoolQueryBuilder queryBuilder = (BoolQueryBuilder) parseQuery(query);
        checkGeneratedJson(query, queryBuilder);

        assertEquals(query, 42, queryBuilder.boost, 0.00001);
        assertEquals(query, "23", queryBuilder.minimumShouldMatch());
        assertEquals(query, "foobar", ((TermQueryBuilder) queryBuilder.must().get(0)).value());
    }

    public void testMinimumShouldMatchNumber() throws IOException {
        String query = "{\"bool\" : {\"must\" : { \"term\" : { \"field\" : \"value\" } }, \"minimum_should_match\" : 1 } }";
        BoolQueryBuilder builder = (BoolQueryBuilder) parseQuery(query);
        assertEquals("1", builder.minimumShouldMatch());
    }

    public void testMinimumShouldMatchNull() throws IOException {
        String query = "{\"bool\" : {\"must\" : { \"term\" : { \"field\" : \"value\" } }, \"minimum_should_match\" : null } }";
        BoolQueryBuilder builder = (BoolQueryBuilder) parseQuery(query);
        assertEquals(null, builder.minimumShouldMatch());
    }

    public void testMustNull() throws IOException {
        String query = "{\"bool\" : {\"must\" : null } }";
        BoolQueryBuilder builder = (BoolQueryBuilder) parseQuery(query);
        assertTrue(builder.must().isEmpty());
    }

    public void testMustNotNull() throws IOException {
        String query = "{\"bool\" : {\"must_not\" : null } }";
        BoolQueryBuilder builder = (BoolQueryBuilder) parseQuery(query);
        assertTrue(builder.mustNot().isEmpty());
    }

    public void testShouldNull() throws IOException {
        String query = "{\"bool\" : {\"should\" : null } }";
        BoolQueryBuilder builder = (BoolQueryBuilder) parseQuery(query);
        assertTrue(builder.should().isEmpty());
    }

    public void testFilterNull() throws IOException {
        String query = "{\"bool\" : {\"filter\" : null } }";
        BoolQueryBuilder builder = (BoolQueryBuilder) parseQuery(query);
        assertTrue(builder.filter().isEmpty());
    }

    /**
     * Check if a filter can be applied to the BoolQuery
     * @throws IOException
     */
    public void testFilter() throws IOException {
        // Test for non null filter
        String query = "{\"bool\" : {\"filter\" : null } }";
        QueryBuilder filter = QueryBuilders.matchAllQuery();
        BoolQueryBuilder builder = (BoolQueryBuilder) parseQuery(query);
        assertFalse(builder.filter(filter).filter().isEmpty());
        assertEquals(builder.filter(filter).filter().get(0), filter);

        // Test for null filter case
        builder = (BoolQueryBuilder) parseQuery(query);
        assertTrue(builder.filter(null).filter().isEmpty());
    }

    /**
     * test that unknown query names in the clauses throw an error
     */
    public void testUnknownQueryName() throws IOException {
        String query = "{\"bool\" : {\"must\" : { \"unknown_query\" : { } } } }";

        XContentParseException ex = expectThrows(XContentParseException.class, () -> parseQuery(query));
        assertEquals("[1:41] [bool] failed to parse field [must]", ex.getMessage());
        Throwable e = ex.getCause();
        assertThat(e.getMessage(), containsString("unknown query [unknown_query]"));

    }

    public void testDeprecation() throws IOException {
        String query = "{\"bool\" : {\"mustNot\" : { \"match_all\" : { } } } }";
        QueryBuilder q = parseQuery(query);
        QueryBuilder expected = new BoolQueryBuilder().mustNot(new MatchAllQueryBuilder());
        assertEquals(expected, q);
        assertWarnings("Deprecated field [mustNot] used, expected [must_not] instead");
    }

    public void testRewrite() throws IOException {
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolean mustRewrite = false;
        if (randomBoolean()) {
            mustRewrite = true;
            boolQueryBuilder.must(new WrapperQueryBuilder(new TermsQueryBuilder(TEXT_FIELD_NAME, "must").toString()));
        }
        if (randomBoolean()) {
            mustRewrite = true;
            boolQueryBuilder.should(new WrapperQueryBuilder(new TermsQueryBuilder(TEXT_FIELD_NAME, "should").toString()));
        }
        if (randomBoolean()) {
            mustRewrite = true;
            boolQueryBuilder.filter(new WrapperQueryBuilder(new TermsQueryBuilder(TEXT_FIELD_NAME, "filter").toString()));
        }
        if (randomBoolean()) {
            mustRewrite = true;
            boolQueryBuilder.mustNot(new WrapperQueryBuilder(new TermsQueryBuilder(TEXT_FIELD_NAME, "must_not").toString()));
        }
        if (mustRewrite == false && randomBoolean()) {
            boolQueryBuilder.must(new TermsQueryBuilder(TEXT_FIELD_NAME, "no_rewrite"));
        }
        QueryBuilder rewritten = boolQueryBuilder.rewrite(createShardContext());
        if (mustRewrite == false && boolQueryBuilder.must().isEmpty()) {
            // if it's empty we rewrite to match all
            assertEquals(rewritten, new MatchAllQueryBuilder());
        } else {
            BoolQueryBuilder rewrite = (BoolQueryBuilder) rewritten;
            if (mustRewrite) {
                assertNotSame(rewrite, boolQueryBuilder);
                if (boolQueryBuilder.must().isEmpty() == false) {
                    assertEquals(new TermsQueryBuilder(TEXT_FIELD_NAME, "must"), rewrite.must().get(0));
                }
                if (boolQueryBuilder.should().isEmpty() == false) {
                    assertEquals(new TermsQueryBuilder(TEXT_FIELD_NAME, "should"), rewrite.should().get(0));
                }
                if (boolQueryBuilder.mustNot().isEmpty() == false) {
                    assertEquals(new TermsQueryBuilder(TEXT_FIELD_NAME, "must_not"), rewrite.mustNot().get(0));
                }
                if (boolQueryBuilder.filter().isEmpty() == false) {
                    assertEquals(new TermsQueryBuilder(TEXT_FIELD_NAME, "filter"), rewrite.filter().get(0));
                }
            } else {
                assertSame(rewrite, boolQueryBuilder);
                if (boolQueryBuilder.must().isEmpty() == false) {
                    assertSame(boolQueryBuilder.must().get(0), rewrite.must().get(0));
                }
            }
        }
    }

    public void testRewriteMultipleTimes() throws IOException {
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.must(new WrapperQueryBuilder(new WrapperQueryBuilder(new MatchAllQueryBuilder().toString()).toString()));
        QueryBuilder rewritten = boolQueryBuilder.rewrite(createShardContext());
        BoolQueryBuilder expected = new BoolQueryBuilder();
        expected.must(new MatchAllQueryBuilder());
        assertEquals(expected, rewritten);

        expected = new BoolQueryBuilder();
        expected.must(new MatchAllQueryBuilder());
        QueryBuilder rewrittenAgain = rewritten.rewrite(createShardContext());
        assertEquals(rewrittenAgain, expected);
        assertEquals(Rewriteable.rewrite(boolQueryBuilder, createShardContext()), expected);
    }

    public void testRewriteWithMatchNone() throws IOException {
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.must(new WrapperQueryBuilder(new WrapperQueryBuilder(new MatchNoneQueryBuilder().toString()).toString()));
        QueryBuilder rewritten = boolQueryBuilder.rewrite(createShardContext());
        assertEquals(new MatchNoneQueryBuilder(), rewritten);

        boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.must(new TermQueryBuilder(TEXT_FIELD_NAME, "bar"));
        boolQueryBuilder.filter(new WrapperQueryBuilder(new WrapperQueryBuilder(new MatchNoneQueryBuilder().toString()).toString()));
        rewritten = boolQueryBuilder.rewrite(createShardContext());
        assertEquals(new MatchNoneQueryBuilder(), rewritten);

        boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.must(new TermQueryBuilder(TEXT_FIELD_NAME, "bar"));
        boolQueryBuilder.filter(
            new BoolQueryBuilder().should(new TermQueryBuilder(TEXT_FIELD_NAME, "bar")).filter(new MatchNoneQueryBuilder())
        );
        rewritten = Rewriteable.rewrite(boolQueryBuilder, createShardContext());
        assertEquals(new MatchNoneQueryBuilder(), rewritten);

        boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.should(new WrapperQueryBuilder(new MatchNoneQueryBuilder().toString()));
        rewritten = Rewriteable.rewrite(boolQueryBuilder, createShardContext());
        assertEquals(new MatchNoneQueryBuilder(), rewritten);

        boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.should(new TermQueryBuilder(TEXT_FIELD_NAME, "bar"));
        boolQueryBuilder.should(new WrapperQueryBuilder(new MatchNoneQueryBuilder().toString()));
        rewritten = Rewriteable.rewrite(boolQueryBuilder, createShardContext());
        assertNotEquals(new MatchNoneQueryBuilder(), rewritten);

        boolQueryBuilder = new BoolQueryBuilder();
        rewritten = Rewriteable.rewrite(boolQueryBuilder, createShardContext());
        assertNotEquals(new MatchNoneQueryBuilder(), rewritten);
    }

    @Override
    public void testMustRewrite() throws IOException {
        QueryShardContext context = createShardContext();
        context.setAllowUnmappedFields(true);
        TermQueryBuilder termQuery = new TermQueryBuilder("unmapped_field", 42);
        BoolQueryBuilder boolQuery = new BoolQueryBuilder();
        boolQuery.must(termQuery);
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> boolQuery.toQuery(context));
        assertEquals("Rewrite first", e.getMessage());
    }

    public void testVisit() {
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.should(new TermQueryBuilder(TEXT_FIELD_NAME, "should"));
        boolQueryBuilder.must(new TermQueryBuilder(TEXT_FIELD_NAME, "must1"));
        boolQueryBuilder.must(new TermQueryBuilder(TEXT_FIELD_NAME, "must2")); // Add a second one to confirm that they both get visited
        boolQueryBuilder.mustNot(new TermQueryBuilder(TEXT_FIELD_NAME, "mustNot"));
        boolQueryBuilder.filter(new TermQueryBuilder(TEXT_FIELD_NAME, "filter"));
        List<QueryBuilder> visitedQueries = new ArrayList<>();
        boolQueryBuilder.visit(createTestVisitor(visitedQueries));
        assertEquals(6, visitedQueries.size());
        Set<Object> set = new HashSet<>(Arrays.asList("should", "must1", "must2", "mustNot", "filter"));

        for (QueryBuilder qb : visitedQueries) {
            if (qb instanceof TermQueryBuilder) {
                set.remove(((TermQueryBuilder) qb).value());
            }
        }

        assertEquals(0, set.size());

    }

    public void testOneMustNotRangeRewritten() throws Exception {
        int from = 10;
        int to = 20;
        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, newIndexWriterConfig(new StandardAnalyzer()));
        addDocument(w, INT_FIELD_NAME, 1);
        DirectoryReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = getIndexSearcher(reader);

        for (boolean includeLower : new boolean[] { true, false }) {
            for (boolean includeUpper : new boolean[] { true, false }) {
                BoolQueryBuilder qb = new BoolQueryBuilder();
                QueryBuilder rq = getRangeQueryBuilder(INT_FIELD_NAME, from, to, includeLower, includeUpper);
                qb.mustNot(rq);

                BoolQueryBuilder rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext(searcher));
                assertFalse(rewritten.mustNot().contains(rq));

                QueryBuilder expectedLowerQuery = getRangeQueryBuilder(INT_FIELD_NAME, null, from, false, !includeLower);
                QueryBuilder expectedUpperQuery = getRangeQueryBuilder(INT_FIELD_NAME, to, null, !includeUpper, true);
                assertEquals(1, rewritten.must().size());

                BoolQueryBuilder nestedBoolQuery = (BoolQueryBuilder) rewritten.must().get(0);
                assertEquals(2, nestedBoolQuery.should().size());
                assertEquals("1", nestedBoolQuery.minimumShouldMatch());
                assertTrue(nestedBoolQuery.should().contains(expectedLowerQuery));
                assertTrue(nestedBoolQuery.should().contains(expectedUpperQuery));
            }
        }
        IOUtils.close(w, reader, dir);
    }

    public void testOneSingleEndedMustNotRangeRewritten() throws Exception {
        // Test a must_not range query with only one endpoint is rewritten correctly
        int from = 10;
        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, newIndexWriterConfig(new StandardAnalyzer()));
        addDocument(w, INT_FIELD_NAME, 1);
        DirectoryReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = getIndexSearcher(reader);

        BoolQueryBuilder qb = new BoolQueryBuilder();
        QueryBuilder rq = getRangeQueryBuilder(INT_FIELD_NAME, from, null, false, false);
        qb.mustNot(rq);
        BoolQueryBuilder rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext(searcher));
        assertFalse(rewritten.mustNot().contains(rq));

        QueryBuilder expectedQuery = getRangeQueryBuilder(INT_FIELD_NAME, null, from, false, true);
        assertEquals(1, rewritten.must().size());
        BoolQueryBuilder nestedBoolQuery = (BoolQueryBuilder) rewritten.must().get(0);
        assertEquals(1, nestedBoolQuery.should().size());
        assertTrue(nestedBoolQuery.should().contains(expectedQuery));
        assertEquals("1", nestedBoolQuery.minimumShouldMatch());

        IOUtils.close(w, reader, dir);
    }

    public void testMultipleComplementAwareOnSameFieldNotRewritten() throws Exception {
        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, newIndexWriterConfig(new StandardAnalyzer()));
        addDocument(w, INT_FIELD_NAME, 1);
        DirectoryReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = getIndexSearcher(reader);

        BoolQueryBuilder qb = new BoolQueryBuilder();
        // Test a field with two ranges is not rewritten
        QueryBuilder rq1of2 = new RangeQueryBuilder(INT_FIELD_NAME).gt(10).lt(20);
        QueryBuilder rq2of2 = new RangeQueryBuilder(INT_FIELD_NAME).gt(30).lt(40);
        qb.mustNot(rq1of2);
        qb.mustNot(rq2of2);
        BoolQueryBuilder rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext(searcher));

        assertTrue(rewritten.mustNot().contains(rq1of2));
        assertTrue(rewritten.mustNot().contains(rq2of2));
        assertEquals(0, rewritten.should().size());

        // Similarly 1 range query and 1 match query on the same field shouldn't be rewritten
        qb = new BoolQueryBuilder();
        qb.mustNot(rq1of2);
        QueryBuilder matchQuery = new MatchQueryBuilder(INT_FIELD_NAME, 200);
        qb.mustNot(matchQuery);
        rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext(searcher));
        assertTrue(rewritten.mustNot().contains(rq1of2));
        assertTrue(rewritten.mustNot().contains(matchQuery));
        assertEquals(0, rewritten.should().size());

        IOUtils.close(w, reader, dir);
    }

    public void testMustNotRewriteDisabledWithoutLeafReaders() throws Exception {
        // If we don't have access the LeafReaderContexts, don't perform the must_not rewrite
        int from = 10;
        int to = 20;

        BoolQueryBuilder qb = new BoolQueryBuilder();
        QueryBuilder rq = getRangeQueryBuilder(INT_FIELD_NAME, from, to, true, true);
        qb.mustNot(rq);

        // Context has no searcher available --> no leaf readers available
        BoolQueryBuilder rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext());
        assertTrue(rewritten.mustNot().contains(rq));
    }

    public void testMustNotRewriteDisabledWithoutExactlyOneValuePerDoc() throws Exception {
        // If the PointValues returned don't show exactly 1 value per doc, don't perform the must_not rewrite
        int from = 10;
        int to = 20;
        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, newIndexWriterConfig(new StandardAnalyzer()));
        addDocument(w, INT_FIELD_NAME, 1, 2, 3); // This doc will have 3 values, so the rewrite shouldn't happen
        addDocument(w, INT_FIELD_NAME, 1);
        DirectoryReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = getIndexSearcher(reader);

        BoolQueryBuilder qb = new BoolQueryBuilder();
        QueryBuilder rq = getRangeQueryBuilder(INT_FIELD_NAME, from, to, true, true);
        qb.mustNot(rq);

        BoolQueryBuilder rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext(searcher));
        assertTrue(rewritten.mustNot().contains(rq));

        IOUtils.close(w, reader, dir);
    }

    public void testOneMustNotNumericMatchQueryRewritten() throws Exception {
        Directory dir = newDirectory();
        IndexWriter w = new IndexWriter(dir, newIndexWriterConfig(new StandardAnalyzer()));
        addDocument(w, INT_FIELD_NAME, 1);
        DirectoryReader reader = DirectoryReader.open(w);
        IndexSearcher searcher = getIndexSearcher(reader);

        BoolQueryBuilder qb = new BoolQueryBuilder();
        int excludedValue = 200;
        QueryBuilder matchQuery = new MatchQueryBuilder(INT_FIELD_NAME, excludedValue);
        qb.mustNot(matchQuery);

        BoolQueryBuilder rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext(searcher));
        assertFalse(rewritten.mustNot().contains(matchQuery));

        QueryBuilder expectedLowerQuery = getRangeQueryBuilder(INT_FIELD_NAME, null, excludedValue, true, false);
        QueryBuilder expectedUpperQuery = getRangeQueryBuilder(INT_FIELD_NAME, excludedValue, null, false, true);
        assertEquals(1, rewritten.must().size());

        BoolQueryBuilder nestedBoolQuery = (BoolQueryBuilder) rewritten.must().get(0);
        assertEquals(2, nestedBoolQuery.should().size());
        assertEquals("1", nestedBoolQuery.minimumShouldMatch());
        assertTrue(nestedBoolQuery.should().contains(expectedLowerQuery));
        assertTrue(nestedBoolQuery.should().contains(expectedUpperQuery));

        // When the QueryShardContext is null, we should not rewrite any match queries as we can't confirm if they're on numeric fields.
        QueryRewriteContext nullContext = mock(QueryRewriteContext.class);
        when(nullContext.convertToShardContext()).thenReturn(null);
        BoolQueryBuilder rewrittenNoContext = (BoolQueryBuilder) Rewriteable.rewrite(qb, nullContext);
        assertTrue(rewrittenNoContext.mustNot().contains(matchQuery));
        assertTrue(rewrittenNoContext.should().isEmpty());

        IOUtils.close(w, reader, dir);
    }

    public void testMustClausesRewritten() throws Exception {
        BoolQueryBuilder qb = new BoolQueryBuilder();

        // Should be moved
        QueryBuilder intTermQuery = new TermQueryBuilder(INT_FIELD_NAME, 200);
        QueryBuilder rangeQuery = new RangeQueryBuilder(INT_FIELD_NAME).gt(10).lt(20);
        // Should be moved to filter clause, the boost applies equally to all matched docs
        QueryBuilder rangeQueryWithBoost = new RangeQueryBuilder(DATE_FIELD_NAME).gt(10).lt(20).boost(2);
        QueryBuilder intTermsQuery = new TermsQueryBuilder(INT_FIELD_NAME, new int[] { 1, 4, 100 });
        QueryBuilder boundingBoxQuery = new GeoBoundingBoxQueryBuilder(GEO_POINT_FIELD_NAME);
        QueryBuilder doubleMatchQuery = new MatchQueryBuilder(DOUBLE_FIELD_NAME, 5.5);

        // Should not be moved
        QueryBuilder textTermQuery = new TermQueryBuilder(TEXT_FIELD_NAME, "bar");
        QueryBuilder textTermsQuery = new TermsQueryBuilder(TEXT_FIELD_NAME, "foo", "bar");
        QueryBuilder textMatchQuery = new MatchQueryBuilder(TEXT_FIELD_NAME, "baz");

        qb.must(intTermQuery);
        qb.must(rangeQuery);
        qb.must(rangeQueryWithBoost);
        qb.must(intTermsQuery);
        qb.must(boundingBoxQuery);
        qb.must(doubleMatchQuery);

        qb.must(textTermQuery);
        qb.must(textTermsQuery);
        qb.must(textMatchQuery);

        BoolQueryBuilder rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, createShardContext());
        for (QueryBuilder clause : List.of(
            intTermQuery,
            rangeQuery,
            rangeQueryWithBoost,
            intTermsQuery,
            boundingBoxQuery,
            doubleMatchQuery
        )) {
            assertFalse(rewritten.must().contains(clause));
            assertTrue(rewritten.filter().contains(clause));
        }
        for (QueryBuilder clause : List.of(textTermQuery, textTermsQuery, textMatchQuery)) {
            assertTrue(rewritten.must().contains(clause));
            assertFalse(rewritten.filter().contains(clause));
        }

        // If we have null QueryShardContext, match/term/terms queries should not be moved as we can't determine if they're numeric.
        QueryRewriteContext nullContext = mock(QueryRewriteContext.class);
        when(nullContext.convertToShardContext()).thenReturn(null);
        rewritten = (BoolQueryBuilder) Rewriteable.rewrite(qb, nullContext);
        for (QueryBuilder clause : List.of(rangeQuery, rangeQueryWithBoost, boundingBoxQuery)) {
            assertFalse(rewritten.must().contains(clause));
            assertTrue(rewritten.filter().contains(clause));
        }
        for (QueryBuilder clause : List.of(textTermQuery, textTermsQuery, textMatchQuery, intTermQuery, intTermsQuery, doubleMatchQuery)) {
            assertTrue(rewritten.must().contains(clause));
            assertFalse(rewritten.filter().contains(clause));
        }
    }

    private QueryBuilder getRangeQueryBuilder(String fieldName, Integer lower, Integer upper, boolean includeLower, boolean includeUpper) {
        RangeQueryBuilder rq = new RangeQueryBuilder(fieldName);
        if (lower != null) {
            if (includeLower) {
                rq.gte(lower);
            } else {
                rq.gt(lower);
            }
        }
        if (upper != null) {
            if (includeUpper) {
                rq.lte(upper);
            } else {
                rq.lt(upper);
            }
        }
        return rq;
    }

    private void addDocument(IndexWriter w, String fieldName, int... values) throws Exception {
        Document d = new Document();
        for (int value : values) {
            d.add(new IntPoint(fieldName, value));
        }
        w.addDocument(d);
        w.commit();
    }

    static IndexSearcher getIndexSearcher(DirectoryReader reader) throws Exception {
        SearchContext searchContext = mock(SearchContext.class);
        return new ContextIndexSearcher(
            reader,
            IndexSearcher.getDefaultSimilarity(),
            IndexSearcher.getDefaultQueryCache(),
            IndexSearcher.getDefaultQueryCachingPolicy(),
            true,
            null,
            searchContext
        );
    }
}
