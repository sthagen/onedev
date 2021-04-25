package io.onedev.server.buildspec.job;

import static io.onedev.server.model.Build.NAME_BRANCH;
import static io.onedev.server.model.Build.NAME_COMMIT;
import static io.onedev.server.model.Build.NAME_JOB;
import static io.onedev.server.model.Build.NAME_PULL_REQUEST;
import static io.onedev.server.model.Build.NAME_TAG;
import static io.onedev.server.search.entity.build.BuildQuery.getRuleName;
import static io.onedev.server.search.entity.build.BuildQueryLexer.And;
import static io.onedev.server.search.entity.build.BuildQueryLexer.Is;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintValidatorContext;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.ws.rs.core.HttpHeaders;

import org.apache.wicket.Component;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.commons.codeassist.InputCompletion;
import io.onedev.commons.codeassist.InputStatus;
import io.onedev.commons.codeassist.InputSuggestion;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.server.buildspec.BuildSpec;
import io.onedev.server.buildspec.BuildSpecAware;
import io.onedev.server.buildspec.NamedElement;
import io.onedev.server.buildspec.job.action.PostBuildAction;
import io.onedev.server.buildspec.job.gitcredential.DefaultCredential;
import io.onedev.server.buildspec.job.gitcredential.GitCredential;
import io.onedev.server.buildspec.job.trigger.JobTrigger;
import io.onedev.server.buildspec.param.ParamUtils;
import io.onedev.server.buildspec.param.spec.ParamSpec;
import io.onedev.server.buildspec.step.CommandStep;
import io.onedev.server.buildspec.step.Step;
import io.onedev.server.event.ProjectEvent;
import io.onedev.server.git.GitUtils;
import io.onedev.server.model.PullRequest;
import io.onedev.server.util.ComponentContext;
import io.onedev.server.util.EditContext;
import io.onedev.server.util.criteria.Criteria;
import io.onedev.server.util.validation.Validatable;
import io.onedev.server.util.validation.annotation.ClassValidating;
import io.onedev.server.web.editable.annotation.ChoiceProvider;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Interpolative;
import io.onedev.server.web.editable.annotation.NameOfEmptyValue;
import io.onedev.server.web.editable.annotation.Patterns;
import io.onedev.server.web.editable.annotation.RetryCondition;
import io.onedev.server.web.editable.annotation.ShowCondition;
import io.onedev.server.web.editable.annotation.SuggestionProvider;
import io.onedev.server.web.util.WicketUtils;

@Editable
@ClassValidating
public class Job implements NamedElement, Serializable, Validatable {

	private static final long serialVersionUID = 1L;
	
	public static final String SELECTION_PREFIX = "jobs/";
	
	public static final String PROP_JOB_DEPENDENCIES = "jobDependencies";
	
	public static final String PROP_REQUIRED_SERVICES = "requiredServices";
	
	public static final String PROP_TRIGGERS = "triggers";
	
	public static final String PROP_STEPS = "steps";
	
	public static final String PROP_RETRY_CONDITION = "retryCondition";
	
	public static final String PROP_POST_BUILD_ACTIONS = "postBuildActions";
	
	private String name;
	
	private List<Step> steps = new ArrayList<>();
	
	private List<ParamSpec> paramSpecs = new ArrayList<>();
	
	private boolean retrieveSource = true;
	
	private Integer cloneDepth;
	
	private GitCredential cloneCredential = new DefaultCredential();

	private List<JobDependency> jobDependencies = new ArrayList<>();
	
	private List<ProjectDependency> projectDependencies = new ArrayList<>();
	
	private List<String> requiredServices = new ArrayList<>();
	
	private String artifacts;
	
	private List<JobReport> reports = new ArrayList<>();

	private List<JobTrigger> triggers = new ArrayList<>();
	
	private List<CacheSpec> caches = new ArrayList<>();

	private String cpuRequirement = "250m";
	
	private String memoryRequirement = "128m";
	
	private long timeout = 3600;
	
	private List<PostBuildAction> postBuildActions = new ArrayList<>();
	
	private String retryCondition = "never";
	
	private int maxRetries = 3;
	
	private int retryDelay = 30;
	
	private transient Map<String, ParamSpec> paramSpecMap;
	
	public Job() {
		steps.add(new CommandStep());
	}
	
	@Editable(order=100, description="Specify name of the job")
	@SuggestionProvider("getNameSuggestions")
	@NotEmpty
	@Override
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
	
	@SuppressWarnings("unused")
	private static List<InputCompletion> getNameSuggestions(InputStatus status) {
		BuildSpec buildSpec = BuildSpec.get();
		if (buildSpec != null) {
			List<String> candidates = new ArrayList<>(buildSpec.getJobMap().keySet());
			buildSpec.getJobs().forEach(it->candidates.remove(it.getName()));
			return BuildSpec.suggestOverrides(candidates, status);
		}
		return new ArrayList<>();
	}

	@Editable(order=200)
	@Size(min=1, max=1000, message="At least one step needs to be configured")
	public List<Step> getSteps() {
		return steps;
	}
	
	public void setSteps(List<Step> steps) {
		this.steps = steps;
	}

	@Editable(order=300, name="Parameter Specs", group="Params & Triggers", description="Optionally define parameter specifications of the job")
	@Valid
	public List<ParamSpec> getParamSpecs() {
		return paramSpecs;
	}

	public void setParamSpecs(List<ParamSpec> paramSpecs) {
		this.paramSpecs = paramSpecs;
	}

	@Editable(order=500, group="Params & Triggers", description="Use triggers to run the job automatically under certain conditions")
	@Valid
	public List<JobTrigger> getTriggers() {
		return triggers;
	}

	public void setTriggers(List<JobTrigger> triggers) {
		this.triggers = triggers;
	}

	@Editable(order=250, group="Source Retrieval", description="Whether or not to retrieve repository files")
	public boolean isRetrieveSource() {
		return retrieveSource;
	}

	public void setRetrieveSource(boolean retrieveSource) {
		this.retrieveSource = retrieveSource;
	}
	
	@Editable(order=251, group="Source Retrieval", description="Optionally specify depth for a shallow clone in order "
			+ "to speed up source retrieval")
	@ShowCondition("isRetrieveSourceEnabled")
	public Integer getCloneDepth() {
		return cloneDepth;
	}

	public void setCloneDepth(Integer cloneDepth) {
		this.cloneDepth = cloneDepth;
	}

	@Editable(order=252, group="Source Retrieval", description="By default code is cloned via an auto-generated credential, "
			+ "which only has read permission over current project. In case the job needs to <a href='$docRoot/pages/push-in-job.md' target='_blank'>push code to server</a>, or want "
			+ "to <a href='$docRoot/pages/clone-submodules-via-ssh.md' target='_blank'>clone private submodules</a>, you should supply custom credential with appropriate permissions here")
	@ShowCondition("isRetrieveSourceEnabled")
	@NotNull
	public GitCredential getCloneCredential() {
		return cloneCredential;
	}

	public void setCloneCredential(GitCredential cloneCredential) {
		this.cloneCredential = cloneCredential;
	}

	@SuppressWarnings("unused")
	private static boolean isRetrieveSourceEnabled() {
		return (boolean) EditContext.get().getInputValue("retrieveSource");
	}

	@Editable(name="Job Dependencies", order=9110, group="Dependencies & Services", description="Job dependencies determines the order and "
			+ "concurrency when run different jobs. You may also specify artifacts to retrieve from upstream jobs")
	@Valid
	public List<JobDependency> getJobDependencies() {
		return jobDependencies;
	}

	public void setJobDependencies(List<JobDependency> jobDependencies) {
		this.jobDependencies = jobDependencies;
	}

	@Editable(name="Project Dependencies", order=9112, group="Dependencies & Services", description="Use project dependency to retrieve "
			+ "artifacts from other projects")
	@Valid
	public List<ProjectDependency> getProjectDependencies() {
		return projectDependencies;
	}

	public void setProjectDependencies(List<ProjectDependency> projectDependencies) {
		this.projectDependencies = projectDependencies;
	}

	@Editable(order=9114, group="Dependencies & Services", description="Optionally specify services required by this job")
	@ChoiceProvider("getServiceChoices")
	public List<String> getRequiredServices() {
		return requiredServices;
	}

	public void setRequiredServices(List<String> requiredServices) {
		this.requiredServices = requiredServices;
	}
	
	@SuppressWarnings("unused")
	private static List<String> getServiceChoices() {
		List<String> choices = new ArrayList<>();
		Component component = ComponentContext.get().getComponent();
		BuildSpecAware buildSpecAware = WicketUtils.findInnermost(component, BuildSpecAware.class);
		if (buildSpecAware != null) {
			BuildSpec buildSpec = buildSpecAware.getBuildSpec();
			if (buildSpec != null) { 
				choices.addAll(buildSpec.getServiceMap().values().stream()
						.map(it->it.getName()).collect(Collectors.toList()));
			}
		}
		return choices;
	}

	@Editable(order=9115, group="Artifacts & Reports", description="Optionally specify files to publish as job artifacts relative to "
			+ "repository root. Use * or ? for pattern match")
	@Interpolative(variableSuggester="suggestVariables")
	@Patterns(path=true)
	@NameOfEmptyValue("No artifacts")
	public String getArtifacts() {
		return artifacts;
	}

	public void setArtifacts(String artifacts) {
		this.artifacts = artifacts;
	}

	@Editable(order=9120, group="Artifacts & Reports", description="Add job reports here")
	@Valid
	public List<JobReport> getReports() {
		return reports;
	}

	public void setReports(List<JobReport> reports) {
		this.reports = reports;
	}

	@Editable(order=9400, group="More Settings", description="Specify condition to retry build upon failure")
	@NotEmpty
	@RetryCondition
	public String getRetryCondition() {
		return retryCondition;
	}

	public void setRetryCondition(String retryCondition) {
		this.retryCondition = retryCondition;
	}

	@Editable(order=9410, group="More Settings", description="Maximum of retries before giving up")
	@Min(value=1, message="This value should not be less than 1")
	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	@Editable(order=9420, group="More Settings", description="Delay for the first retry in seconds. "
			+ "Delay of subsequent retries will be calculated using an exponential back-off "
			+ "based on this delay")
	@Min(value=1, message="This value should not be less than 1")
	public int getRetryDelay() {
		return retryDelay;
	}

	public void setRetryDelay(int retryDelay) {
		this.retryDelay = retryDelay;
	}
	
	@Editable(order=10050, name="CPU Requirement", group="More Settings", description="Specify CPU requirement of the job. "
			+ "Refer to <a href='https://kubernetes.io/docs/concepts/configuration/manage-compute-resources-container/#meaning-of-cpu' target='_blank'>kubernetes documentation</a> for details")
	@Interpolative(variableSuggester="suggestVariables")
	@NotEmpty
	public String getCpuRequirement() {
		return cpuRequirement;
	}

	public void setCpuRequirement(String cpuRequirement) {
		this.cpuRequirement = cpuRequirement;
	}

	@Editable(order=10060, group="More Settings", description="Specify memory requirement of the job. "
			+ "Refer to <a href='https://kubernetes.io/docs/concepts/configuration/manage-compute-resources-container/#meaning-of-memory' target='_blank'>kubernetes documentation</a> for details")
	@Interpolative(variableSuggester="suggestVariables")
	@NotEmpty
	public String getMemoryRequirement() {
		return memoryRequirement;
	}

	public void setMemoryRequirement(String memoryRequirement) {
		this.memoryRequirement = memoryRequirement;
	}

	@Editable(order=10100, group="More Settings", description="Cache specific paths to speed up job execution. "
			+ "For instance for node.js projects, you may cache folder <tt>/root/.npm</tt> to avoid downloading "
			+ "node modules for subsequent job executions")
	@Valid
	public List<CacheSpec> getCaches() {
		return caches;
	}

	public void setCaches(List<CacheSpec> caches) {
		this.caches = caches;
	}

	@Editable(order=10500, group="More Settings", description="Specify timeout in seconds")
	public long getTimeout() {
		return timeout;
	}

	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}
	
	@Editable(order=10600, name="Post Build Actions", group="More Settings")
	@Valid
	public List<PostBuildAction> getPostBuildActions() {
		return postBuildActions;
	}
	
	public void setPostBuildActions(List<PostBuildAction> postBuildActions) {
		this.postBuildActions = postBuildActions;
	}
	
	@Nullable
	public JobTriggerMatch getTriggerMatch(ProjectEvent event) {
		for (JobTrigger trigger: getTriggers()) {
			SubmitReason reason = trigger.matches(event, this);
			if (reason != null)
				return new JobTriggerMatch(trigger, reason);
		}
		return null;
	}

	@Override
	public boolean isValid(ConstraintValidatorContext context) {
		boolean isValid = true;
		
		Set<String> keys = new HashSet<>();
		Set<String> paths = new HashSet<>();
		for (CacheSpec cache: caches) {
			if (!keys.add(cache.getKey())) {
				isValid = false;
				context.buildConstraintViolationWithTemplate("Duplicate key (" + cache.getKey() + ")")
						.addPropertyNode("caches").addConstraintViolation();
			}
			if (!paths.add(cache.getPath())) {
				isValid = false;
				context.buildConstraintViolationWithTemplate("Duplicate path (" + cache.getPath() + ")")
						.addPropertyNode("caches").addConstraintViolation();
			} 
		}

		Set<String> dependencyJobNames = new HashSet<>();
		for (JobDependency dependency: jobDependencies) {
			if (!dependencyJobNames.add(dependency.getJobName())) {
				isValid = false;
				context.buildConstraintViolationWithTemplate("Duplicate dependency (" + dependency.getJobName() + ")")
						.addPropertyNode("jobDependencies").addConstraintViolation();
			} 
		}
		
		Set<String> dependencyProjectNames = new HashSet<>();
		for (ProjectDependency dependency: projectDependencies) {
			if (!dependencyProjectNames.add(dependency.getProjectName())) {
				isValid = false;
				context.buildConstraintViolationWithTemplate("Duplicate dependency (" + dependency.getProjectName() + ")")
						.addPropertyNode("projectDependencies").addConstraintViolation();
			}
		}
		
		Set<String> paramSpecNames = new HashSet<>();
		for (ParamSpec paramSpec: paramSpecs) {
			if (!paramSpecNames.add(paramSpec.getName())) {
				isValid = false;
				context.buildConstraintViolationWithTemplate("Duplicate parameter spec (" + paramSpec.getName() + ")")
						.addPropertyNode("paramSpecs").addConstraintViolation();
			} 
		}
		
		if (getRetryCondition() != null) { 
			try {
				io.onedev.server.buildspec.job.retrycondition.RetryCondition.parse(this, getRetryCondition());
			} catch (Exception e) {
				String message = e.getMessage();
				if (message == null)
					message = "Malformed retry condition";
				context.buildConstraintViolationWithTemplate(message)
						.addPropertyNode(PROP_RETRY_CONDITION)
						.addConstraintViolation();
				isValid = false;
			}
		}
		
		if (isValid) {
			for (int triggerIndex=0; triggerIndex<getTriggers().size(); triggerIndex++) {
				JobTrigger trigger = getTriggers().get(triggerIndex);
				try {
					ParamUtils.validateParams(getParamSpecs(), trigger.getParams());
				} catch (Exception e) {
					String errorMessage = String.format("Error validating job parameters (item: #%s, error message: %s)", 
							(triggerIndex+1), e.getMessage());
					context.buildConstraintViolationWithTemplate(errorMessage)
							.addPropertyNode(PROP_TRIGGERS)
							.addConstraintViolation();
					isValid = false;
				}
			}
		}
		
		if (!isValid)
			context.disableDefaultConstraintViolation();
		
		return isValid;
	}
	
	public Map<String, ParamSpec> getParamSpecMap() {
		if (paramSpecMap == null)
			paramSpecMap = ParamUtils.getParamSpecMap(paramSpecs);
		return paramSpecMap;
	}
	
	public static String getBuildQuery(ObjectId commitId, String jobName, 
			@Nullable String refName, @Nullable PullRequest request) {
		String query = "" 
				+ Criteria.quote(NAME_COMMIT) + " " + getRuleName(Is) + " " + Criteria.quote(commitId.name()) 
				+ " " + getRuleName(And) + " "
				+ Criteria.quote(NAME_JOB) + " " + getRuleName(Is) + " " + Criteria.quote(jobName);
		if (request != null) {
			query = query 
					+ " " + getRuleName(And) + " " 
					+ Criteria.quote(NAME_PULL_REQUEST) + " " + getRuleName(Is) + " " + Criteria.quote("#" + request.getNumber());
		}
		if (refName != null) {
			String branch = GitUtils.ref2branch(refName);
			if (branch != null) {
				query = query 
					+ " " + getRuleName(And) + " " 
					+ Criteria.quote(NAME_BRANCH) + " " + getRuleName(Is) + " " + Criteria.quote(branch);
			} 
			String tag = GitUtils.ref2tag(refName);
			if (tag != null) {
				query = query 
					+ " " + getRuleName(And) + " " 
					+ Criteria.quote(NAME_TAG) + " " + getRuleName(Is) + " " + Criteria.quote(tag);
			} 
		}
		return query;
	}
	
	public static List<String> getChoices() {
		List<String> choices = new ArrayList<>();
		Component component = ComponentContext.get().getComponent();
		BuildSpecAware buildSpecAware = WicketUtils.findInnermost(component, BuildSpecAware.class);
		if (buildSpecAware != null) {
			BuildSpec buildSpec = buildSpecAware.getBuildSpec();
			if (buildSpec != null) {
				choices.addAll(buildSpec.getJobMap().values().stream()
						.map(it->it.getName()).collect(Collectors.toList()));
			}
			JobAware jobAware = WicketUtils.findInnermost(component, JobAware.class);
			if (jobAware != null) {
				Job job = jobAware.getJob();
				if (job != null)
					choices.remove(job.getName());
			}
		}
		return choices;
	}

	@Nullable
	public static String getToken(HttpServletRequest request) {
		String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (bearer != null && bearer.startsWith(KubernetesHelper.BEARER + " "))
			return bearer.substring(KubernetesHelper.BEARER.length() + 1);
		else
			return null;
	}
	
	@SuppressWarnings("unused")
	private static List<InputSuggestion> suggestVariables(String matchWith) {
		return BuildSpec.suggestVariables(matchWith);
	}
	
}
