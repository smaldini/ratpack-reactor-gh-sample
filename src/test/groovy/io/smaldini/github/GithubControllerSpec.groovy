package io.smaldini.github

import com.jayway.restassured.path.json.JsonPath
import ratpack.groovy.test.LocalScriptApplicationUnderTest
import ratpack.groovy.test.TestHttpClient
import ratpack.groovy.test.TestHttpClients
import spock.lang.Specification

class GithubControllerSpec extends Specification {

	static final String reactorOrganizationResult = '''
{"reactor/reactor":3,"reactor/spring-xd":0,"reactor/reactor-quickstart":0,"reactor/projectreactor.org":0,
"reactor/reactor-data":0,"reactor/reactor-atmosphere":0}
'''

	def aut = new LocalScriptApplicationUnderTest()
	@Delegate
	TestHttpClient client = TestHttpClients.testHttpClient(aut)

	def setup() {
		resetRequest()
	}

	def "captures output"() {
		setup:

		when:
			get "orgs/reactor/rank"

		then:
			with(new JsonResponse()) {
				jsonOutput['reactor/reactor'] == 3
			}
	}

	class JsonResponse {
		private JsonPath path = GithubControllerSpec.this.response.jsonPath()

		Map getJsonOutput() {
			path.getMap("")
		}
	}
}
