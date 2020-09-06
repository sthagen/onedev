package io.onedev.server.search.entity.build;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import io.onedev.server.model.Build;
import io.onedev.server.model.PullRequestVerification;
import io.onedev.server.search.entity.EntityCriteria;

public class RequiredByPullRequestsCriteria extends EntityCriteria<Build> {

	private static final long serialVersionUID = 1L;

	@Override
	public Predicate getPredicate(Root<Build> root, CriteriaBuilder builder) {
		Join<?, ?> join = root.join(Build.PROP_VERIFICATIONS, JoinType.LEFT);
		join.on(builder.equal(join.get(PullRequestVerification.PROP_REQUIRED), true)); 
		return join.isNotNull();
	}

	@Override
	public boolean matches(Build build) {
		return build.getVerifications().stream().anyMatch(it->it.isRequired());
	}

	@Override
	public String toStringWithoutParens() {
		return BuildQuery.getRuleName(BuildQueryLexer.RequiredByPullRequests);
	}

}
