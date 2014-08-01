import io.smaldini.github.GithubModule
import io.smaldini.github.GithubService
import ratpack.groovy.templating.TemplatingModule
import ratpack.http.HttpResponseChunk
import ratpack.jackson.JacksonModule
import reactor.tuple.Tuple2

import static ratpack.groovy.Groovy.groovyTemplate
import static ratpack.groovy.Groovy.ratpack
import static ratpack.jackson.Jackson.json
import static ratpack.http.HttpResponseChunks.httpResponseChunks

ratpack {

    bindings {
        add new JacksonModule()
        add new GithubModule()
        config(TemplatingModule).staticallyCompile = true
    }

    handlers {
        assets "public"

        /**
         * Homepage
         */
        get {
            render groovyTemplate("index.html", title: "API home")
        }

        /**
         * Chunked Capture GET localhost:port/orgs/[org]/rank where [org] is an existing Github Org
         */
        get("chunk/orgs/:org/rank") { GithubService githubService ->
            //stream github Repos asynchronously via http chunks
            render httpResponseChunks(
                    githubService.findRepositoriesByOrganizationId((String) allPathTokens.org).

                    //bind a sub stream for pull requests size per repo
                    flatMap { String repo ->
                        githubService.countPullRequestsByRepository(repo).map { Integer pr ->
                            "repo $repo pr $pr"
                        }
                    }.

                    map {
                        new HttpResponseChunk(it.toString() + "\n")
                    }
            )
        }

        /**
         * Capture GET localhost:port/orgs/[org]/rank where [org] is an existing Github Org
         */
        get("orgs/:org/rank") { GithubService githubService ->

            //fetch github Repos asynchronously
            githubService.findRepositoriesByOrganizationId((String) allPathTokens.org).
            //bind a sub stream for pull requests size per repo
                    flatMap { String repo ->
                        githubService.countPullRequestsByRepository(repo).map { Integer pr ->

                            //just a bit of noise to be sure of what's happening for the cautious reader :)
                            println "repo $repo pr $pr"

                            //Tuple is a flat structure, good we don't need more for a pair of repo/prs
                            Tuple2.of(repo, pr)
                        }
                    }.

            // sort the previously collected repo/pr pair list per PR (t2) and Repo name (t1), the staging container will
            // keep elements to sort in memory until complete signal is detected
                    sort { Tuple2<String, Integer> e1, Tuple2<String, Integer> e2 ->
                        e2.t2 == e1.t2 ? e1.t1 <=> e2.t1 : e2.t2 <=> e1.t2
                    }.

            //slice and group the first 5 elements
                    limit(5).

            //note that buffer() without max-capacity argument is collecting data until complete signal
                    buffer().

            //render the list as json, using a "map[repo-name] = pr" structure for Jackson marshalling
                    consume { Iterable<Tuple2<String, Integer>> tupleList ->
                        render json(tupleList.inject([:]) { map, Tuple2<String, Integer> tuple -> map[tuple.t1] = tuple.t2; map })
                    }.

            //catch any errors and return them to the http response
                    when(Exception) { Exception ex ->
                        ex.printStackTrace()
                        response.status(503)
                        render json(ex.message)
                    }
        }

    }
}