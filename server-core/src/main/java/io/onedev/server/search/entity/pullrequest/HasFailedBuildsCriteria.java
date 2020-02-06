package io.onedev.server.search.entity.pullrequest;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import io.onedev.server.model.Build;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestBuild;
import io.onedev.server.search.entity.EntityCriteria;

public class HasFailedBuildsCriteria extends EntityCriteria<PullRequest> {

	private static final long serialVersionUID = 1L;

	@Override
	public Predicate getPredicate(Root<PullRequest> root, CriteriaBuilder builder) {
		Join<?, ?> join = root
				.join(PullRequest.PROP_PULL_REQUEST_BUILDS, JoinType.LEFT)
				.join(PullRequestBuild.PROP_BUILD, JoinType.INNER);
		Path<?> status = join.get(Build.STATUS);
		
		join.on(builder.or(
				builder.equal(status, Build.Status.FAILED), 
				builder.equal(status, Build.Status.CANCELLED), 
				builder.equal(status, Build.Status.TIMED_OUT)));
		return join.isNotNull();
	}

	@Override
	public boolean matches(PullRequest request) {
		for (PullRequestBuild build: request.getPullRequestBuilds()) {
			if (build.getBuild().getStatus() == Build.Status.FAILED
					|| build.getBuild().getStatus() == Build.Status.CANCELLED
					|| build.getBuild().getStatus() == Build.Status.TIMED_OUT) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String asString() {
		return PullRequestQuery.getRuleName(PullRequestQueryLexer.HasFailedBuilds);
	}

}
