package io.onedev.server.model.support.issue.changedata;

import io.onedev.server.model.Issue;
import io.onedev.server.model.PullRequest;

public class IssuePullRequestDiscardedData extends IssuePullRequestData {

	private static final long serialVersionUID = 1L;
	
	public IssuePullRequestDiscardedData(PullRequest request) {
		super(request);
	}
	
	@Override
	public String getActivity(Issue withIssue) {
		if (withIssue != null)
			return "discarded pull request fixing issue " + withIssue.describe();
		else
			return "discarded pull request fixing this issue";
	}
	
}
