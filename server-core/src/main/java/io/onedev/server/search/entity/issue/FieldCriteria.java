package io.onedev.server.search.entity.issue;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import com.google.common.base.Preconditions;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.issue.fieldspec.FieldSpec;
import io.onedev.server.model.Issue;
import io.onedev.server.model.IssueField;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.util.query.IssueQueryConstants;

public abstract class FieldCriteria extends IssueCriteria {

	private static final long serialVersionUID = 1L;

	private String fieldName;
	
	public FieldCriteria(String fieldName) {
		this.fieldName = fieldName;
	}

	public String getFieldName() {
		return fieldName;
	}

	@Override
	public final Predicate getPredicate(Root<Issue> root, CriteriaBuilder builder) {
		Join<?, ?> join = root.join(IssueQueryConstants.ATTR_FIELDS, JoinType.LEFT);
		Predicate valuePredicate = getValuePredicate(join, builder);
		if (valuePredicate != null) {
			join.on(builder.and(
					builder.equal(join.get(IssueField.ATTR_NAME), getFieldName()), 
					getValuePredicate(join, builder)));
			return join.isNotNull();
		} else {
			join.on(builder.and(
					builder.equal(join.get(IssueField.ATTR_NAME), getFieldName()), 
					builder.or(builder.isNull(join.get(IssueField.ATTR_VALUE)))));
			Join<?, ?> join2 = root.join(IssueQueryConstants.ATTR_FIELDS, JoinType.LEFT);
			join2.on(builder.equal(join2.get(IssueField.ATTR_NAME), getFieldName()));
			return builder.or(join.isNotNull(), join2.isNull());
		}
	}

	/**
	 * @return predicate of field value. <tt>null</tt> to indicate that this field is empty   
	 */
	@Nullable
	protected abstract Predicate getValuePredicate(Join<?, ?> field, CriteriaBuilder builder);
	
	@Override
	public Collection<String> getUndefinedFields() {
		Set<String> undefinedFields = new HashSet<>();
		GlobalIssueSetting issueSetting = OneDev.getInstance(SettingManager.class).getIssueSetting();
		if (!IssueQueryConstants.QUERY_FIELDS.contains(fieldName) 
				&& issueSetting.getFieldSpec(fieldName) == null) {
			undefinedFields.add(fieldName);
		}
		return undefinedFields;
	}
	
	public FieldSpec getFieldSpec() {
		SettingManager settingManager = OneDev.getInstance(SettingManager.class);
		return Preconditions.checkNotNull(settingManager.getIssueSetting().getFieldSpec(fieldName));
	}

	@Override
	public void onRenameField(String oldField, String newField) {
		if (oldField.equals(fieldName))
			fieldName = newField;
	}

	@Override
	public boolean onDeleteField(String fieldName) {
		return fieldName.equals(this.fieldName);
	}
	
}
