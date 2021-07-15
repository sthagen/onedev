package io.onedev.server.plugin.executor.docker;

import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.getCacheInstances;
import static io.onedev.k8shelper.KubernetesHelper.installGitCert;
import static io.onedev.k8shelper.KubernetesHelper.preprocess;
import static io.onedev.k8shelper.KubernetesHelper.readPlaceholderValues;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.stringifyPosition;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.validation.ConstraintValidatorContext;

import org.apache.commons.lang3.SystemUtils;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;

import io.onedev.commons.launcher.bootstrap.Bootstrap;
import io.onedev.commons.launcher.loader.AppLoader;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.k8shelper.CacheInstance;
import io.onedev.k8shelper.CheckoutExecutable;
import io.onedev.k8shelper.CloneInfo;
import io.onedev.k8shelper.CommandExecutable;
import io.onedev.k8shelper.CompositeExecutable;
import io.onedev.k8shelper.LeafExecutable;
import io.onedev.k8shelper.LeafHandler;
import io.onedev.k8shelper.ServerExecutable;
import io.onedev.k8shelper.SshCloneInfo;
import io.onedev.server.OneDev;
import io.onedev.server.buildspec.Service;
import io.onedev.server.buildspec.job.EnvVar;
import io.onedev.server.buildspec.job.JobContext;
import io.onedev.server.buildspec.job.JobManager;
import io.onedev.server.buildspec.job.log.StyleBuilder;
import io.onedev.server.git.config.GitConfig;
import io.onedev.server.model.support.RegistryLogin;
import io.onedev.server.model.support.administration.jobexecutor.JobExecutor;
import io.onedev.server.plugin.executor.docker.DockerExecutor.TestData;
import io.onedev.server.util.ExceptionUtils;
import io.onedev.server.util.PKCS12CertExtractor;
import io.onedev.server.util.ServerConfig;
import io.onedev.server.util.SimpleLogger;
import io.onedev.server.util.concurrent.CapacityRunner;
import io.onedev.server.util.patternset.PatternSet;
import io.onedev.server.util.validation.Validatable;
import io.onedev.server.util.validation.annotation.ClassValidating;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Horizontal;
import io.onedev.server.web.editable.annotation.NameOfEmptyValue;
import io.onedev.server.web.editable.annotation.OmitName;
import io.onedev.server.web.util.Testable;

@Editable(order=200, description="This executor runs build jobs as docker containers on OneDev server")
@ClassValidating
@Horizontal
public class DockerExecutor extends JobExecutor implements Testable<TestData>, Validatable {

	private static final long serialVersionUID = 1L;
	
	private static final Logger logger = LoggerFactory.getLogger(DockerExecutor.class);

	private List<RegistryLogin> registryLogins = new ArrayList<>();
	
	private int capacity = Runtime.getRuntime().availableProcessors();
	
	private String runOptions;
	
	private String dockerExecutable;
	
	private transient CapacityRunner capacityRunner;
	
	private transient volatile String outerInstallPath;

	@Editable(order=400, description="Specify login information for docker registries if necessary")
	public List<RegistryLogin> getRegistryLogins() {
		return registryLogins;
	}

	public void setRegistryLogins(List<RegistryLogin> registryLogins) {
		this.registryLogins = registryLogins;
	}

	@Editable(order=475, description="Specify max number of concurrent jobs being executed. Each job execution "
			+ "will launch a separate docker container. Defaults to number of processors in the system")
	public int getCapacity() {
		return capacity;
	}

	public void setCapacity(int capacity) {
		this.capacity = capacity;
	}

	@Editable(order=50050, group="More Settings", description="Optionally specify options to run container. For instance, you may use <tt>-m 2g</tt> "
			+ "to limit memory of created container to be 2 giga bytes")
	public String getRunOptions() {
		return runOptions;
	}

	public void setRunOptions(String runOptions) {
		this.runOptions = runOptions;
	}

	@Editable(order=50100, group="More Settings", description="Optionally specify docker executable, for instance <i>/usr/local/bin/docker</i>. "
			+ "Leave empty to use docker executable in PATH")
	@NameOfEmptyValue("Use default")
	public String getDockerExecutable() {
		return dockerExecutable;
	}

	public void setDockerExecutable(String dockerExecutable) {
		this.dockerExecutable = dockerExecutable;
	}

	private Commandline newDocker() {
		if (getDockerExecutable() != null)
			return new Commandline(getDockerExecutable());
		else
			return new Commandline("docker");
	}
	
	private synchronized CapacityRunner getCapacityRunner() {
		if (capacityRunner == null)
			capacityRunner = new CapacityRunner(capacity);
		return capacityRunner;
	}
	
	private File getCacheHome() {
		return new File(Bootstrap.getSiteDir(), "cache"); 
	}

	private void createNetwork(String network, JobContext jobContext, SimpleLogger jobLogger) {
		AtomicBoolean networkExists = new AtomicBoolean(false);
		Commandline docker = newDocker();
		docker.addArgs("network", "ls", "-q", "--filter", "name=" + network);
		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				networkExists.set(true);
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}
			
		}).checkReturnCode();
		
		if (networkExists.get()) {
			clearNetwork(network, jobLogger);
		} else {
			docker.clearArgs();
			docker.addArgs("network", "create");
			if (SystemUtils.IS_OS_WINDOWS)
				docker.addArgs("-d", "nat");
			docker.addArgs(network);
			docker.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.debug(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}).checkReturnCode();
		}
	}
	
	private void deleteNetwork(String network, SimpleLogger jobLogger) {
		clearNetwork(network, jobLogger);
		
		Commandline docker = newDocker();
		docker.clearArgs();
		docker.addArgs("network", "rm", network);
		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.debug(line);
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}
			
		}).checkReturnCode();
	}
	
	private void clearNetwork(String network, SimpleLogger jobLogger) {
		Commandline docker = newDocker();
		
		List<String> containerIds = new ArrayList<>();
		docker.addArgs("ps", "-a", "-q", "--filter", "network=" + network);
		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				containerIds.add(line);
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}
			
		}).checkReturnCode();
		
		for (String container: containerIds) {
			docker.clearArgs();
			docker.addArgs("container", "stop", container);
			docker.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.debug(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}).checkReturnCode();
			
			docker.clearArgs();
			docker.addArgs("container", "rm", container);
			docker.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.debug(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}).checkReturnCode();
		}
	}
	
	@SuppressWarnings("resource")
	private void startService(String network, Service jobService, SimpleLogger jobLogger) {
		jobLogger.log("Creating service container...");
		
		String containerName = network + "-service-" + jobService.getName();
		
		Commandline docker = newDocker();
		docker.clearArgs();
		docker.addArgs("run", "-d", "--name=" + containerName, "--network=" + network, 
				"--network-alias=" + jobService.getName());
		for (EnvVar var: jobService.getEnvVars()) 
			docker.addArgs("--env", var.getName() + "=" + var.getValue());
		docker.addArgs(jobService.getImage());
		if (jobService.getArguments() != null) {
			for (String token: StringUtils.parseQuoteTokens(jobService.getArguments()))
				docker.addArgs(token);
		}
		
		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}
			
		}).checkReturnCode();

		jobLogger.log("Waiting for service to be ready...");
		
		ObjectMapper jsonReader = OneDev.getInstance(ObjectMapper.class);		
		while (true) {
			StringBuilder builder = new StringBuilder();
			docker.clearArgs();
			docker.addArgs("inspect", containerName);
			docker.execute(new LineConsumer(UTF_8.name()) {

				@Override
				public void consume(String line) {
					builder.append(line).append("\n");
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}).checkReturnCode();

			JsonNode stateNode;
			try {
				stateNode = jsonReader.readTree(builder.toString()).iterator().next().get("State");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			
			if (stateNode.get("Status").asText().equals("running")) {
				docker.clearArgs();
				docker.addArgs("exec", containerName);
				
				if (SystemUtils.IS_OS_WINDOWS) 
					docker.addArgs("cmd", "/c", jobService.getReadinessCheckCommand());
				else 
					docker.addArgs("sh", "-c", jobService.getReadinessCheckCommand());
				
				ExecutionResult result = docker.execute(new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log("Service readiness check: " + line);
					}
					
				}, new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log("Service readiness check: " + line);
					}
					
				});
				if (result.getReturnCode() == 0) {
					jobLogger.log("Service is ready");
					break;
				}
			} else if (stateNode.get("Status").asText().equals("exited")) {
				if (stateNode.get("OOMKilled").asText().equals("true"))  
					jobLogger.error("Out of memory");
				else if (stateNode.get("Error").asText().length() != 0)  
					jobLogger.error(stateNode.get("Error").asText());
				
				docker.clearArgs();
				docker.addArgs("logs", containerName);
				docker.execute(new LineConsumer(UTF_8.name()) {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}
					
				}, new LineConsumer(UTF_8.name()) {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}
					
				}).checkReturnCode();
				
				throw new ExplicitException(String.format("Service '" + jobService.getName() + "' is stopped unexpectedly"));
			}
			
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}		
	}
	
	private LineConsumer newStyledLogger(SimpleLogger jobLogger) {
		return new LineConsumer(StandardCharsets.UTF_8.name()) {

			private final StyleBuilder styleBuilder = new StyleBuilder();
			
			@Override
			public void consume(String line) {
				jobLogger.log(line, styleBuilder);
			}
			
		};
	}
	
	private LineConsumer newErrorLogger(SimpleLogger jobLogger) {
		return new LineConsumer(StandardCharsets.UTF_8.name()) {

			@Override
			public void consume(String line) {
				jobLogger.warning(line);
			}
			
		};
	}
	
	@Override
	public void execute(String jobToken, JobContext jobContext) {
		File hostBuildHome = FileUtils.createTempDir("onedev-build");
		try {
			SimpleLogger jobLogger = jobContext.getLogger();
			getCapacityRunner().call(new Callable<Void>() {
	
				@Override
				public Void call() {
					String network = getName() + "-" + jobContext.getProjectName() + "-" 
							+ jobContext.getBuildNumber() + "-" + jobContext.getRetried();

					jobLogger.log(String.format("Executing job (executor: %s, network: %s)...", getName(), network));
					jobContext.notifyJobRunning();
					
					JobManager jobManager = OneDev.getInstance(JobManager.class);		
					File hostCacheHome = getCacheHome();
					FileUtils.createDir(hostCacheHome);
					
					jobLogger.log("Allocating job caches...") ;
					Map<CacheInstance, Date> cacheInstances = getCacheInstances(hostCacheHome);
					Map<CacheInstance, String> cacheAllocations = jobManager.allocateJobCaches(jobToken, new Date(), cacheInstances);
					preprocess(hostCacheHome, cacheAllocations, new Consumer<File>() {
	
						@Override
						public void accept(File directory) {
							cleanDirAsRoot(directory);
						}
						
					});
						
					login(jobLogger);
					
					createNetwork(network, jobContext, jobLogger);
					try {
						for (Service jobService: jobContext.getServices()) {
							jobLogger.log("Starting service (name: " + jobService.getName() + ", image: " + jobService.getImage() + ")...");
							startService(network, jobService, jobLogger);
						}
						
						AtomicReference<File> workspaceCache = new AtomicReference<>(null);
						for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
							if (PathUtils.isCurrent(entry.getValue())) {
								workspaceCache.set(entry.getKey().getDirectory(hostCacheHome));
								break;
							}
						}
						
						File hostWorkspace;
						if (workspaceCache.get() != null) {
							hostWorkspace = workspaceCache.get();
						} else { 
							hostWorkspace = new File(hostBuildHome, "workspace");
							FileUtils.createDir(hostWorkspace);
						}
						
						AtomicReference<File> hostHome = new AtomicReference<>(null);
						try {						
							jobLogger.log("Copying job dependencies...");
							jobContext.copyDependencies(hostWorkspace);
	
							String containerBuildHome;
							String containerWorkspace;
							String containerEntryPoint;
							if (SystemUtils.IS_OS_WINDOWS) {
								containerBuildHome = "C:\\onedev-build";
								containerWorkspace = "C:\\onedev-build\\workspace";
								containerEntryPoint = "cmd";
							} else {
								containerBuildHome = "/onedev-build";
								containerWorkspace = "/onedev-build/workspace";
								containerEntryPoint = "sh";
							}
							
							jobContext.reportJobWorkspace(containerWorkspace);
							CompositeExecutable entryExecutable = new CompositeExecutable(jobContext.getActions());
							
							List<String> errorMessages = new ArrayList<>();
							
							entryExecutable.execute(new LeafHandler() {

								@Override
								public boolean execute(LeafExecutable executable, List<Integer> position) {
									String stepNames = entryExecutable.getNamesAsString(position);
									jobLogger.log("Running step \"" + stepNames + "\"...");
									
									if (executable instanceof CommandExecutable) {
										CommandExecutable commandExecutable = (CommandExecutable) executable;
										String[] containerCommand;
										if (SystemUtils.IS_OS_WINDOWS) {
											if (hostHome.get() != null)
												containerCommand = new String[] {"/c", "xcopy /Y /S /K /Q /H /R C:\\Users\\%USERNAME%\\onedev\\* C:\\Users\\%USERNAME% > nul && C:\\onedev-build\\job-commands.bat"};						
											else
												containerCommand = new String[] {"/c", "C:\\onedev-build\\job-commands.bat"};						
											File scriptFile = new File(hostBuildHome, "job-commands.bat");
											try {
												FileUtils.writeLines(
														scriptFile, 
														new ArrayList<>(replacePlaceholders(commandExecutable.getCommands(), hostBuildHome)), 
														"\r\n");
											} catch (IOException e) {
												throw new RuntimeException(e);
											}
										} else {
											if (hostHome.get() != null)
												containerCommand = new String[] {"-c", "cp -r -f -p /root/onedev/. /root && sh /onedev-build/job-commands.sh"};
											else
												containerCommand = new String[] {"/onedev-build/job-commands.sh"};
											File scriptFile = new File(hostBuildHome, "job-commands.sh");
											try {
												FileUtils.writeLines(
														scriptFile, 
														new ArrayList<>(replacePlaceholders(commandExecutable.getCommands(), hostBuildHome)), 
														"\n");
											} catch (IOException e) {
												throw new RuntimeException(e);
											}
										}
										
										String containerName = network + "-step-" + stringifyPosition(position);
										Commandline docker = newDocker();
										docker.clearArgs();
										docker.addArgs("run", "--name=" + containerName, "--network=" + network);
										if (getRunOptions() != null)
											docker.addArgs(StringUtils.parseQuoteTokens(getRunOptions()));
										
										docker.addArgs("-v", getOuterPath(hostBuildHome.getAbsolutePath()) + ":" + containerBuildHome);
										if (workspaceCache.get() != null)
											docker.addArgs("-v", getOuterPath(workspaceCache.get().getAbsolutePath()) + ":" + containerWorkspace);
										for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
											if (!PathUtils.isCurrent(entry.getValue())) {
												String hostCachePath = entry.getKey().getDirectory(hostCacheHome).getAbsolutePath();
												String containerCachePath = PathUtils.resolve(containerWorkspace, entry.getValue());
												docker.addArgs("-v", getOuterPath(hostCachePath) + ":" + containerCachePath);
											}
										}
										
										if (SystemUtils.IS_OS_LINUX) 
											docker.addArgs("-v", "/var/run/docker.sock:/var/run/docker.sock");
										
										if (hostHome.get() != null) {
											String outerPath = getOuterPath(hostHome.get().getAbsolutePath());
											if (SystemUtils.IS_OS_WINDOWS) {
												docker.addArgs("-v",  outerPath + ":C:\\Users\\ContainerAdministrator\\onedev");
												docker.addArgs("-v",  outerPath + ":C:\\Users\\ContainerUser\\onedev");
											} else { 
												docker.addArgs("-v", outerPath + ":/root/onedev");
											}
										}

										if (commandExecutable.isUseTTY())
											docker.addArgs("-t");
										docker.addArgs("-w", containerWorkspace, "--entrypoint=" + containerEntryPoint);
										
										docker.addArgs(commandExecutable.getImage());
										docker.addArgs(containerCommand);
										
										ProcessKiller killer = new ProcessKiller() {
											
											@Override
											public void kill(Process process, String executionId) {
												jobLogger.log("Stopping step container...");
												Commandline cmd = newDocker();
												cmd.addArgs("stop", containerName);
												cmd.execute(new LineConsumer() {
			
													@Override
													public void consume(String line) {
														DockerExecutor.logger.debug(line);
													}
													
												}, new LineConsumer() {
			
													@Override
													public void consume(String line) {
														jobLogger.log(line);
													}
													
												}).checkReturnCode();
											}
											
										};
										
										ExecutionResult result = docker.execute(newStyledLogger(jobLogger), newErrorLogger(jobLogger), null, killer);
										if (result.getReturnCode() != 0) {
											errorMessages.add("Step \"" + stepNames + "\": Command failed with exit code " + result.getReturnCode());
											return false;
										} else {
											return true;
										}
									} else if (executable instanceof CheckoutExecutable) {
										try {
											CheckoutExecutable checkoutExecutable = (CheckoutExecutable) executable;
											jobLogger.log("Checking out code...");
											if (hostHome.get() == null)
												hostHome.set(FileUtils.createTempDir());
											Commandline git = new Commandline(AppLoader.getInstance(GitConfig.class).getExecutable());	
											git.workingDir(hostWorkspace).environments().put("HOME", hostHome.get().getAbsolutePath());
	
											CloneInfo cloneInfo = checkoutExecutable.getCloneInfo();
											
											cloneInfo.writeAuthData(hostHome.get(), git, newStyledLogger(jobLogger), newErrorLogger(jobLogger));
											
											List<String> trustCertContent = new ArrayList<>();
											ServerConfig serverConfig = OneDev.getInstance(ServerConfig.class); 
											File keystoreFile = serverConfig.getKeystoreFile();
											if (keystoreFile != null) {
												String password = serverConfig.getKeystorePassword();
												for (Map.Entry<String, String> entry: new PKCS12CertExtractor(keystoreFile, password).extact().entrySet()) 
													trustCertContent.addAll(Splitter.on('\n').trimResults().splitToList(entry.getValue()));
											}
											if (serverConfig.getTrustCertsDir() != null) {
												for (File file: serverConfig.getTrustCertsDir().listFiles()) {
													if (file.isFile()) 
														trustCertContent.addAll(FileUtils.readLines(file, UTF_8));
												}
											}
				
											if (!trustCertContent.isEmpty()) {
												installGitCert(new File(hostHome.get(), "trust-cert.pem"), trustCertContent, 
														git, newStyledLogger(jobLogger), newErrorLogger(jobLogger));
											}
	
											int cloneDepth = checkoutExecutable.getCloneDepth();
											
											cloneRepository(hostWorkspace, jobContext.getProjectGitDir().getAbsolutePath(), 
													jobContext.getCommitId().name(), cloneDepth, git, 
													newStyledLogger(jobLogger), newErrorLogger(jobLogger));
											
											git.clearArgs();
											git.addArgs("remote", "add", "origin", cloneInfo.getCloneUrl());
											git.execute(newStyledLogger(jobLogger), newErrorLogger(jobLogger)).checkReturnCode();
											
											if (new File(hostWorkspace, ".gitmodules").exists()) {
												if (SystemUtils.IS_OS_WINDOWS || !(cloneInfo instanceof SshCloneInfo)) {
													jobLogger.log("Retrieving submodules...");
													
													git.clearArgs();
													git.addArgs("submodule", "update", "--init", "--recursive", "--force", "--quiet");
													if (cloneDepth != 0)
														git.addArgs("--depth=" + cloneDepth);						
													git.execute(newStyledLogger(jobLogger), newErrorLogger(jobLogger)).checkReturnCode();
												} else {
													/*
													 * We need to update submodules within a helper image in order to use our own .ssh folder. 
													 * Specifying HOME env to change ~/.ssh folder does not have effect on Linux 
													 */
													Commandline cmd = newDocker();
													String containerName = network + "-submodule-update-helper";
													String homeOuterPath = getOuterPath(hostHome.get().getAbsolutePath());
													String workspaceOuterPath = getOuterPath(hostWorkspace.getAbsolutePath());
													cmd.addArgs("run", "--name=" + containerName, "-v", homeOuterPath + ":/root", 
															"-v", workspaceOuterPath+ ":/git", "--rm", "alpine/git", 
															"submodule", "update", "--init", "--recursive", "--force", "--quiet");	
													if (cloneDepth != 0)
														cmd.addArgs("--depth=" + cloneDepth);						
				
													jobLogger.log("Retrieving submodules with helper image...");
													
													cmd.execute(newStyledLogger(jobLogger), newErrorLogger(jobLogger), null, new ProcessKiller() {
														
														@Override
														public void kill(Process process, String executionId) {
															jobLogger.log("Stopping submodule update helper container...");
															Commandline cmd = newDocker();
															cmd.addArgs("stop", containerName);
															cmd.execute(new LineConsumer() {
						
																@Override
																public void consume(String line) {
																	DockerExecutor.logger.debug(line);
																}
																
															}, new LineConsumer() {
						
																@Override
																public void consume(String line) {
																	jobLogger.log(line);
																}
																
															}).checkReturnCode();
														}
														
													}).checkReturnCode();
												}
											}		
											
											return true;
										} catch (Exception e) {
											String errorMessage = ExceptionUtils.getExpectedError(e);
											if (errorMessage == null) 
												errorMessage = Throwables.getStackTraceAsString(e);
											errorMessages.add("Step \"" + stepNames + "\" is failed: " + errorMessage);
											return false;
										}
									} else {
										ServerExecutable serverExecutable = (ServerExecutable) executable;
										
										File filesDir = FileUtils.createTempDir();
										try {
											Collection<String> placeholders = serverExecutable.getPlaceholders();
											Map<String, String> placeholderValues = readPlaceholderValues(hostBuildHome, placeholders);
											PatternSet filePatterns = new PatternSet(
													new HashSet<>(replacePlaceholders(serverExecutable.getIncludeFiles(), placeholderValues)), 
													new HashSet<>(replacePlaceholders(serverExecutable.getExcludeFiles(), placeholderValues)));

											int baseLen = hostWorkspace.getAbsolutePath().length()+1;
											for (File file: filePatterns.listFiles(hostWorkspace)) {
												try {
													FileUtils.copyFile(file, new File(filesDir, file.getAbsolutePath().substring(baseLen)));
												} catch (IOException e) {
													throw new RuntimeException(e);
												}
											}

											Map<String, byte[]> outputFiles = jobContext.runServerStep(position, filesDir, placeholderValues, jobLogger);
											
											if (outputFiles != null) {
												for (Map.Entry<String, byte[]> entry: outputFiles.entrySet()) {
													FileUtils.writeByteArrayToFile(
															new File(hostBuildHome, entry.getKey()), 
															entry.getValue());
												}
											}
											
											return true;
										} catch (Exception e) {
											String errorMessage = ExceptionUtils.getExpectedError(e);
											if (errorMessage == null) 
												errorMessage = Throwables.getStackTraceAsString(e);
											errorMessages.add("Step \"" + stepNames + "\" is failed: " + errorMessage);
											return false;
										} finally {
											FileUtils.deleteDir(filesDir);
										}
									}
								}

								@Override
								public void skip(LeafExecutable executable, List<Integer> position) {
									jobLogger.log("Skipping step \"" + entryExecutable.getNamesAsString(position) + "\"...");
								}
								
							}, new ArrayList<>());

							if (!errorMessages.isEmpty())
								throw new ExplicitException(errorMessages.iterator().next());
							
							jobLogger.log("Reporting job caches...");
							
							jobManager.reportJobCaches(jobToken, getCacheInstances(hostCacheHome).keySet());
							
							return null;
						} finally {
							if (hostHome.get() != null)
								FileUtils.deleteDir(hostHome.get());
						}
					} finally {
						deleteNetwork(network, jobLogger);
					}
				}
				
			});
		} finally {
			cleanDirAsRoot(hostBuildHome);
			FileUtils.deleteDir(hostBuildHome);
		}
	}

	private void login(SimpleLogger jobLogger) {
		for (RegistryLogin login: getRegistryLogins()) {
			if (login.getRegistryUrl() != null)
				jobLogger.log(String.format("Login to docker registry '%s'...", login.getRegistryUrl()));
			else
				jobLogger.log("Login to official docker registry...");
			Commandline cmd = newDocker();
			cmd.addArgs("login", "-u", login.getUserName(), "--password-stdin");
			if (login.getRegistryUrl() != null)
				cmd.addArgs(login.getRegistryUrl());
			ByteArrayInputStream input;
			try {
				input = new ByteArrayInputStream(login.getPassword().getBytes(UTF_8.name()));
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
			cmd.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.debug(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}, input).checkReturnCode();
		}
	}
	
	private boolean hasOptions(String[] arguments, String... options) {
		for (String argument: arguments) {
			for (String option: options) {
				if (option.startsWith("--")) {
					if (argument.startsWith(option + "=") || argument.equals(option))
						return true;
				} else if (option.startsWith("-")) {
					if (argument.startsWith(option))
						return true;
				} else {
					throw new ExplicitException("Invalid option: " + option);
				}
			}
		}
		return false;
	}
	
	@Override
	public boolean isValid(ConstraintValidatorContext context) {
		boolean isValid = true;
		Set<String> registryUrls = new HashSet<>();
		for (RegistryLogin login: getRegistryLogins()) {
			if (!registryUrls.add(login.getRegistryUrl())) {
				isValid = false;
				String message;
				if (login.getRegistryUrl() != null)
					message = "Duplicate login entry for registry '" + login.getRegistryUrl() + "'";
				else
					message = "Duplicate login entry for official registry";
				context.buildConstraintViolationWithTemplate(message)
						.addPropertyNode("registryLogins").addConstraintViolation();
				break;
			}
		}
		if (getRunOptions() != null) {
			String[] arguments = StringUtils.parseQuoteTokens(getRunOptions());
			String reservedOptions[] = new String[] {"-w", "--workdir", "-d", "--detach", "-a", "--attach", "-t", "--tty", 
					"-i", "--interactive", "--rm", "--restart", "--name"}; 
			if (hasOptions(arguments, reservedOptions)) {
				StringBuilder errorMessage = new StringBuilder("Can not use options: "
						+ Joiner.on(", ").join(reservedOptions));
				context.buildConstraintViolationWithTemplate(errorMessage.toString())
						.addPropertyNode("runOptions").addConstraintViolation();
				isValid = false;
			} 
		}
		if (!isValid)
			context.disableDefaultConstraintViolation();
		return isValid;
	}
	
	private String getOuterPath(String hostPath) {
		String hostInstallPath = Bootstrap.installDir.getAbsolutePath();
		Preconditions.checkState(hostPath.startsWith(hostInstallPath + "/")
				|| hostPath.startsWith(hostInstallPath + "\\"));
		if (outerInstallPath == null) {
			if (Bootstrap.isInDocker()) {
				AtomicReference<String> installDirRef = new AtomicReference<>(null);
				Commandline docker = newDocker();
				String inspectFormat = String.format(
						"{{range .Mounts}} {{if eq .Destination \"%s\"}} {{.Source}} {{end}} {{end}}", 
						hostInstallPath);
				docker.addArgs("inspect", "-f", inspectFormat, System.getenv("HOSTNAME"));						
				docker.execute(new LineConsumer() {
		
					@Override
					public void consume(String line) {
						installDirRef.set(line.trim());
					}
					
				}, new LineConsumer() {
		
					@Override
					public void consume(String line) {
						logger.error(line);
					}
					
				}).checkReturnCode();
				
				outerInstallPath = Preconditions.checkNotNull(installDirRef.get());
			} else {
				outerInstallPath = hostInstallPath;
			}
		}
		return outerInstallPath + hostPath.substring(hostInstallPath.length());
	}
	
	@Override
	public void test(TestData testData, SimpleLogger jobLogger) {
		login(jobLogger);
		
		jobLogger.log("Running container...");
		File workspaceDir = null;
		File cacheDir = null;

		Commandline cmd = newDocker();
		try {
			workspaceDir = Bootstrap.createTempDir("workspace");
			cacheDir = new File(getCacheHome(), UUID.randomUUID().toString());
			FileUtils.createDir(cacheDir);
			
			cmd.clearArgs();
			cmd.addArgs("run", "--rm");
			if (getRunOptions() != null)
				cmd.addArgs(StringUtils.parseQuoteTokens(getRunOptions()));
			String containerWorkspacePath;
			String containerCachePath;
			if (SystemUtils.IS_OS_WINDOWS) {
				containerWorkspacePath = "C:\\onedev-build\\workspace";
				containerCachePath = "C:\\onedev-build\\cache";
			} else {
				containerWorkspacePath = "/onedev-build/workspace";
				containerCachePath = "/onedev-build/cache";
			}
			cmd.addArgs("-v", getOuterPath(workspaceDir.getAbsolutePath()) + ":" + containerWorkspacePath);
			cmd.addArgs("-v", getOuterPath(cacheDir.getAbsolutePath()) + ":" + containerCachePath);
			
			cmd.addArgs("-w", containerWorkspacePath);
			cmd.addArgs(testData.getDockerImage());
			
			if (SystemUtils.IS_OS_WINDOWS) 
				cmd.addArgs("cmd", "/c", "echo hello from container");
			else 
				cmd.addArgs("sh", "-c", "echo hello from container");
			
			cmd.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}).checkReturnCode();
		} finally {
			if (workspaceDir != null)
				FileUtils.deleteDir(workspaceDir);
			if (cacheDir != null)
				FileUtils.deleteDir(cacheDir);
		}
		
		if (!SystemUtils.IS_OS_WINDOWS) {
			jobLogger.log("Checking busybox...");
			cmd = newDocker();
			cmd.addArgs("run", "--rm", "busybox", "sh", "-c", "echo hello from busybox");			
			cmd.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}
				
			}).checkReturnCode();
		}
	}
	
	public void cleanDirAsRoot(File dir) {
		if (SystemUtils.IS_OS_WINDOWS || Bootstrap.isInDocker()) {
			FileUtils.cleanDir(dir);
		} else {
			Commandline cmd = newDocker();
			String containerPath = "/dir-to-clean";
			cmd.addArgs("run", "-v", dir.getAbsolutePath() + ":" + containerPath, "--rm", 
					"busybox", "sh", "-c", "rm -rf " + containerPath + "/*");			
			cmd.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.info(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Error response from daemon"))
						logger.error(line);
					else
						logger.info(line);
				}
				
			}).checkReturnCode();
		}
	}
	
	@Editable(name="Specify a Docker Image to Test Against")
	public static class TestData implements Serializable {

		private static final long serialVersionUID = 1L;

		private String dockerImage;

		@Editable
		@OmitName
		@NotEmpty
		public String getDockerImage() {
			return dockerImage;
		}

		public void setDockerImage(String dockerImage) {
			this.dockerImage = dockerImage;
		}
		
	}

}