package io.onedev.server.search.entity.build;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import io.onedev.server.model.Build;

import io.onedev.server.search.entity.EntityCriteria;

public class AssociatedWithPullRequestsCriteria extends EntityCriteria<Build> {

	private static final long serialVersionUID = 1L;

	@Override
	public Predicate getPredicate(Root<Build> root, CriteriaBuilder builder) {
		return builder.isNotEmpty(root.get(Build.PROP_PULL_REQUEST_BUILDS)); 
	}

	@Override
	public boolean matches(Build build) {
		return !build.getPullRequestBuilds().isEmpty();
	}

	@Override
	public String asString() {
		return BuildQuery.getRuleName(BuildQueryLexer.AssociatedWithPullRequests);
	}

}
