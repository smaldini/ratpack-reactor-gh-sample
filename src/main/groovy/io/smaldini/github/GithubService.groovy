package io.smaldini.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectReader
import com.ning.http.client.AsyncCompletionHandler
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.Response
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import reactor.core.Environment
import reactor.event.registry.CachingRegistry
import reactor.event.registry.Registry
import reactor.function.Consumer
import reactor.rx.Promise
import reactor.rx.Stream
import reactor.rx.spec.Streams

import java.util.concurrent.TimeUnit

import static reactor.event.selector.Selectors.object

/**
 * Github Service Layer.
 * A Simple HTTP asynchronous gateway to Github RESTful services.
 * Typically a Ratpack application will bind this component within a Module.
 *
 * @author Stephane Maldini
 * @since 1.0
 */
@Slf4j
@CompileStatic
class GithubService {

	final static private String URI_PARAM_ACCESS_TOKEN = "access_token"
	final static private String URI_PARAM_ORG = ":org"
	final static private String URI_PARAM_REPO = ":repo"

	final private AsyncHttpClient asyncHttpClient = new AsyncHttpClient()
	final private String githubApiUrl = "https://api.github.com/"
	final private String orgsApiSuffix = "orgs/$URI_PARAM_ORG/repos"
	final private String pullRequestsApiSuffix = "repos/$URI_PARAM_REPO/pulls"
	final private Environment environment = new Environment()
	final private Registry<List<String>> repoCache = new CachingRegistry<List<String>>()
	final private Registry<Integer> numberPullRequestCache = new CachingRegistry<Integer>()
	final private int reaperPeriod = 60000
	final private ObjectReader reader
	final private String apiToken

	GithubService(ObjectReader reader, String apiToken = null) {
		this.reader = reader
		this.apiToken = apiToken

		 //clean github http result caches every reaperPeriod
		environment.rootTimer.schedule(new Consumer<Long>() {
			@Override
			void accept(Long aLong) {
				repoCache.clear()
				numberPullRequestCache.clear()
			}
		}, reaperPeriod, TimeUnit.MILLISECONDS)
	}

	/**
	 * Asynchronously request all repositories full name per Organization id, returning a {@link Stream} sequence of
	 * each repository. The repo list result is cached for a given organization id key.
	 *
	 * @param unescapedOrgId
	 * @return
	 */
	Stream<String> findRepositoriesByOrganizationId(final String unescapedOrgId) {
		//escape whitespace
		final String orgId = unescapedOrgId?.trim()

		//try cache first
		final matchingCaches = repoCache.select(orgId)
		if(matchingCaches){
			//return a pre-populated Stream
			return Streams.defer(matchingCaches.get(0).object, environment)
		}

		//prepare a deferred stream
		final stream = Streams.<String> defer()

		//call yo github asynchronously
		get(githubApiUrl + orgsApiSuffix.replace(URI_PARAM_ORG, orgId), stream){ Response response ->
			//jackson stuff for reading a JSON list from root ( [ {...}, {...} ]
			JsonNode json = reader.readTree(response.responseBodyAsStream)
			Iterator<JsonNode> iterator = json.elements()

			//reading full_name and appending to results
			List<String> result = []
			while (iterator.hasNext()) {
				result << iterator.next().path('full_name').textValue()
			}

			//hydrate cache
			repoCache.register(object(orgId), result)

			//notify the deferred Stream
			for(repo in result){
				stream << repo
			}
			stream.broadcastComplete()

		}

		//return the Stream consumer-side only to the caller
		stream
	}

	/**
	 * Asynchronously request the pull requests list size per repo and return a {@link Promise} for
	 * further downstream. The size result is cached for a given repository full name key.
	 * processing
	 *
	 * @param unescapedRepogId
	 * @return
	 */
	Stream<Integer> countPullRequestsByRepository(final String unescapedRepoId) {
		final String repoId = unescapedRepoId?.trim()

		//try cache first
		def matchingCaches = numberPullRequestCache.select(repoId)
		if(matchingCaches){
			//return a pre-populated success promise
			return Streams.defer(matchingCaches.get(0).object, environment)
		}

		//prepare deferred promise
		final stream = Streams.<Integer> defer(environment)

		//call yo github asynchronously
		get(githubApiUrl + pullRequestsApiSuffix.replace(URI_PARAM_REPO, repoId), stream){ Response response ->
			//Jackson stuff for counting the number of elements from a root list ([ {...}, {...}] )
			JsonNode json = reader.readTree(response.responseBodyAsStream)
			int result = json.elements().size()

			//hydrate cache
			numberPullRequestCache.register(object(repoId), result)

			//notify Promise
			stream << result
			stream.broadcastComplete()
		}

		//return the Stream
		stream
	}

	/**
	 * Send an asynchronous GET HTTP request to the {@param url} defined, invoking {@param action} on complete and
	 * routing any error to the {@param deferredResult}
	 *
	 * @param url
	 * @param deferredResult
	 * @param action
	 * @return
	 */
	private <E> void get(final String url, final Stream<E> deferredResult, final Closure action){
		//create http builder
		final request = asyncHttpClient.prepareGet(url)

		//use provided api token if any (bypass rating limit)
		if (apiToken) {
			request.addQueryParameter(URI_PARAM_ACCESS_TOKEN, apiToken)
			//log.trace 'authenticated request'
		}

		//run http client
		request.execute(new AsyncCompletionHandler<Response>() {
			@Override
			Response onCompleted(Response response) throws Exception {

				if (response.statusCode != 200) {
					deferredResult.broadcastError new GithubException("github service returned: $response.statusCode")
					return response
				}

				action(response)
				response
			}

			@Override
			void onThrowable(Throwable t) {
				//GithubService.log.error url, t
				deferredResult.broadcastError new GithubException("github service can't be contated: $t.message")
			}
		})
	}
}
