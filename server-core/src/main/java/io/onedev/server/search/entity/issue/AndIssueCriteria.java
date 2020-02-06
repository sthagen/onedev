package io.onedev.server.search.entity.issue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import io.onedev.server.model.Issue;
import io.onedev.server.search.entity.AndEntityCriteria;
import io.onedev.server.util.ValueSetEdit;
import io.onedev.server.web.component.issue.workflowreconcile.UndefinedFieldValue;

public class AndIssueCriteria extends IssueCriteria {
	
	private static final long serialVersionUID = 1L;

	private final List<IssueCriteria> criterias;
	
	public AndIssueCriteria(List<IssueCriteria> criterias) {
		this.criterias = criterias;
	}

	@Override
	public Predicate getPredicate(Root<Issue> root, CriteriaBuilder builder) {
		return new AndEntityCriteria<Issue>(criterias).getPredicate(root, builder);
	}

	@Override
	public boolean matches(Issue issue) {
		return new AndEntityCriteria<Issue>(criterias).matches(issue);
	}

	@Override
	public String asString() {
		return new AndEntityCriteria<Issue>(criterias).asString();
	}

	@Override
	public Collection<String> getUndefinedStates() {
		List<String> undefinedStates = new ArrayList<>();
		for (IssueCriteria criteria: criterias)
			undefinedStates.addAll(criteria.getUndefinedStates());
		return undefinedStates;
	}

	@Override
	public void onRenameState(String oldName, String newName) {
		for (IssueCriteria criteria: criterias)
			criteria.onRenameState(oldName, newName);
	}

	@Override
	public boolean onDeleteState(String stateName) {
		for (Iterator<IssueCriteria> it = criterias.iterator(); it.hasNext();) {
			if (it.next().onDeleteState(stateName))
				it.remove();
		}
		return criterias.isEmpty();
	}
	
	@Override
	public Collection<String> getUndefinedFields() {
		Set<String> undefinedFields = new HashSet<>();
		for (IssueCriteria criteria: criterias)
			undefinedFields.addAll(criteria.getUndefinedFields());
		return undefinedFields;
	}

	@Override
	public void onRenameField(String oldName, String newName) {
		for (IssueCriteria criteria: criterias)
			criteria.onRenameField(oldName, newName);
	}

	@Override
	public boolean onDeleteField(String fieldName) {
		for (Iterator<IssueCriteria> it = criterias.iterator(); it.hasNext();) {
			if (it.next().onDeleteField(fieldName))
				it.remove();
		}
		return criterias.isEmpty();
	}

	@Override
	public boolean onEditFieldValues(String fieldName, ValueSetEdit valueSetEdit) {
		for (Iterator<IssueCriteria> it = criterias.iterator(); it.hasNext();) {
			if (it.next().onEditFieldValues(fieldName, valueSetEdit))
				it.remove();
		}
		return criterias.isEmpty();
	}

	@Override
	public Collection<UndefinedFieldValue> getUndefinedFieldValues() {
		Set<UndefinedFieldValue> undefinedFieldValues = new HashSet<>();
		for (IssueCriteria criteria: criterias)
			undefinedFieldValues.addAll(criteria.getUndefinedFieldValues());
		return undefinedFieldValues;
	}

	@Override
	public void fill(Issue issue) {
		for (IssueCriteria criteria: criterias)
			criteria.fill(issue);
	}
	
}
