package io.smaldini.github

/**
 * @author Stephane Maldini
 */
class GithubException extends Exception {

	GithubException(String s) {
		super(s)
	}

	@Override
	Throwable fillInStackTrace() {
		null
	}
}
