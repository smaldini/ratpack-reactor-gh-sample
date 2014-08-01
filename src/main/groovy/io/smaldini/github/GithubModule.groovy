package io.smaldini.github

import com.fasterxml.jackson.databind.ObjectReader
import com.google.inject.AbstractModule
import com.google.inject.Provides
import groovy.transform.CompileStatic
import ratpack.launch.LaunchConfig

@CompileStatic
class GithubModule extends AbstractModule {

	@Provides
	@com.google.inject.Singleton
	GithubService gitHubApi(LaunchConfig launchConfig, ObjectReader reader) {
		new GithubService(reader, launchConfig.getOther('github.apiToken', null), launchConfig)
	}

	@Override
	protected void configure() {

	}
}
