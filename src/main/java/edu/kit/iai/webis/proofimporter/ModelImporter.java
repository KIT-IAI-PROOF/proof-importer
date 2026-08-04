/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.proofimporter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.google.gson.Gson;

import edu.kit.iai.webis.proofutils.wrapper.Attachment;
import edu.kit.iai.webis.proofutils.wrapper.Program;
import edu.kit.iai.webis.proofutils.wrapper.Template;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Workflow;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.service.ConfigManagerService;

/**
 * PROOF utility to import workflows into the configuration database
 * When run with CLI arguments (--template, --workflow, etc.), performs imports
 * When run with no CLI arguments, stays in service mode for REST API requests
 */
@SpringBootApplication
@ComponentScan(basePackages = { "edu.kit.iai.webis.proofimporter", "edu.kit.iai.webis.proofmodels", "edu.kit.iai.webis.proofutils" })
@EnableJpaRepositories(basePackages = "edu.kit.iai.webis.proofimporter.repositories")
@EntityScan("edu.kit.iai.webis.proofimporter.model")
public class ModelImporter implements CommandLineRunner {

	private final ConfigManagerService configManagerService;
	private final JSONElementChecker jsonElementChecker;
	@Autowired
	private ConfigurableApplicationContext context;

	private final Gson gson;

	private final Map<String, Block> importedBlocks = new HashMap<>();
	private final Map<String, Program> importedPrograms = new HashMap<>();
	private final Map<String, String> cliArguments = new HashMap<>();

	private final static String ARG_ATTACHMENTS_DIR = "--proofAttachmentsDir";
	private final static String ARG_OVERWRITE = "--overwriteDBContents";
	private final static String ARG_PRINT = "--printDBContents";
	private final static String ARG_PATH = "--importPath";
	private final static String ARG_SAVE = "--save";
	private final static String ARG_WORKFLOW_NAME = "--workflow";
    private final static String ARG_BLOCK_NAME = "--block";
    private final static String ARG_TEMPLATE_NAME = "--template";
    private final static String ARG_LOGGING_LEVEL = "--loggingLevel";
	private final static String ARG_SUB_DIRS = "--subdirectories";
	private final static String ARG_CHECK_REFERENCES = "--checkReferencesReferences";
	private final static String PROOF_DB = "proof";

	private final static String DEFAULT_IMPORT_COLOR = "#3d3d3d";
	private final static String DEFAULT_IMPORT_USER = "proof";

	private final static int ALL = 0, WORKFLOWS = 1, BLOCKS = 2, PROGRAMS = 3, ATTACHMENTS = 4, CODE = 5;

	boolean overwriteDBContents = false;
	boolean testOnly = true;

	int[] numOfErrors = new int[1];
	int[] numOfCheckErrors = new int[1];

	/**
	 * Return code enum for import operations
	 */
	public enum ReturnCode {
		IMPORT_SUCCESS,
		ID_EXISTS,
		ERROR
	}

	/**
	 * Result class for import operations
	 */
	public static class ImportResult {
		public final ReturnCode returnCode;
		public final String message;
		public final long duration;

		public ImportResult(ReturnCode returnCode, String message, long duration) {
			this.returnCode = returnCode;
			this.message = message;
			this.duration = duration;
		}
	}

	public static void main(final String... args) {
		try {
			SpringApplication.run(ModelImporter.class, args);
		} catch (Throwable t) {
			t.printStackTrace();
		}
		System.out.println("\ndone ...\n");
	}

	public ModelImporter(
			final ConfigManagerService configManagerService,
			final JSONElementChecker jsonElementChecker,
			Gson gson) {
		this.configManagerService = configManagerService;
		this.jsonElementChecker = jsonElementChecker;
		this.gson = gson;
	}

	/**
	 */
	@Override
	public void run(final String... args) {
		LoggingHelper.printColored(true);
		this.scanArgumentValues(args);

		// If running in service mode (no CLI arguments), skip CLI processing and let REST API handle requests
		if (this.cliArguments.isEmpty()) {
			LoggingHelper.debug().log("No CLI arguments provided - running in service mode");
			return;
		}

		LoggingHelper.info().log("== ModelImporter running in CLI mode...");

		String logLevel = this.cliArguments.get(ARG_LOGGING_LEVEL);
		LoggingHelper.setLogLevel(logLevel != null ? logLevel : "INFO");

		if (this.getBooleanArg(ARG_PRINT, false) && !this.getBooleanArg(ARG_OVERWRITE, false) && !this.getBooleanArg(ARG_SAVE, false)) {
			LoggingHelper.info().log("Retrieving the database contents of DB '" + PROOF_DB + "' ... ");
			this.printDatabaseContents();
		}
		else {
//			this.importJson(PROOF_DB);
			this.importElements();
		}
		System.out.println("closing context");
		this.context.close();
	}

	private void scanArgumentValues(final String... args) {
		for (int i = 0; i < args.length; i += 2) {
			int index = args[i].indexOf('=');
			if (index >= 0) {
				this.cliArguments.put(args[i].substring(0, index), args[i].substring(index + 1));
				LoggingHelper.debug().log("Arg: " + args[i].substring(0, index) + " = " + args[i].substring(index + 1));
				i--;
			} else {
				this.cliArguments.put(args[i], args[i + 1]);
				LoggingHelper.debug().log("Arg: " + args[i] + " = " + args[i + 1]);
			}
		}
		this.overwriteDBContents = this.getBooleanArg(ARG_OVERWRITE, false);
		this.testOnly = !this.getBooleanArg(ARG_SAVE, true);

	}

	private final int[] occurences = new int[6];

	/**
	 * Generic method to load and validate a JSON element from a file
	 * @param filePath the path to the JSON file
	 * @param elementTypeName name of the element type for logging (e.g., "template", "workflow")
	 * @return the loaded element, or null if loading failed
	 */
	private Object loadJsonElement(String filePath, String elementTypeName) {
		if (filePath == null || filePath.isBlank()) {
			LoggingHelper.error().log("importing JSON file for the desired %s failed due to missing file name!", elementTypeName);
			return null;
		}

		final Path path = new FileSystemResource(filePath).getFile().toPath();

		if (!Files.exists(path) || !Files.isRegularFile(path)) {
			LoggingHelper.error().log("importing JSON file for the desired %s failed! File %s does not exist or is no regular file.", elementTypeName, path);
			return null;
		}

		if (!path.getFileName().toString().endsWith(".json")) {
			LoggingHelper.error().log("importing JSON file for the desired %s failed: the selected File is no JSON file!", elementTypeName);
			return null;
		}

		LoggingHelper.info().log("importing JSON file '%s'", path.toString());

		final var element = this.jsonElementChecker.loadAndCheckJsonFile(path);

		if (element == null) {
			LoggingHelper.error().log("importing JSON file for the desired %s failed due to errors occured (see above)", elementTypeName);
			return null;
		}

		return element;
	}

	/**
	 * Ensures the workflow's step-based config has a valid ID.
	 * Generates a new UUID if the current config ID already exists in the database.
	 *
	 * @param workflow the workflow to process
	 * @return true if the step-based config is valid and processed successfully, false otherwise
	 */
	private boolean checkStepBasedConfigId(Workflow workflow) {
		// Check the step based config.
		String stepBasedConfigId = workflow.getStepBasedConfig() != null ? workflow.getStepBasedConfig().getId() : null;
		if (stepBasedConfigId == null) {
			LoggingHelper.error().log("\tStep based config reference is null!");
			return false;
		}
		
		String newStepBasedConfigId = stepBasedConfigId;
		int numTries = 0;
		// If step based config with the same id already exists in the database, store it with a new uuid
		while (containsStepBasedConfig(newStepBasedConfigId, false) && numTries++ < 5) {
			newStepBasedConfigId = UUID.randomUUID().toString();
		}
		
		if (!newStepBasedConfigId.equals(stepBasedConfigId)) {
			LoggingHelper.warn().log("\tStep based config '" + stepBasedConfigId + "' found in the database. Creating with new uuid: " + newStepBasedConfigId);
			workflow.getStepBasedConfig().setId(newStepBasedConfigId);
		} else if (numTries >= 5) {
			LoggingHelper.error().log("\tStep based config '" + stepBasedConfigId + "' found in the database. Could not generate new uuid (" + numTries + " tries). Import aborted.");
			return false;
		}
		
		return true;
	}

	private void importElements() {
		String attachmentsDir = this.cliArguments.get(ARG_ATTACHMENTS_DIR);
		boolean checkReferences = this.getBooleanArg(ARG_CHECK_REFERENCES, true);

		if( this.cliArguments.containsKey(ARG_TEMPLATE_NAME)) {
			String templatePath = this.cliArguments.get(ARG_TEMPLATE_NAME);
			ImportResult result = this.importTemplate(templatePath, attachmentsDir, !this.testOnly, this.overwriteDBContents, checkReferences);
			if (result.returnCode == ReturnCode.ERROR) {
				LoggingHelper.error().log(result.message);
			}
			else {
				LoggingHelper.info().log(result.message);
			}
		}
		else if( this.cliArguments.containsKey(ARG_WORKFLOW_NAME)) {
			String workflowPath = this.cliArguments.get(ARG_WORKFLOW_NAME);
			ImportResult result = this.importWorkflow(workflowPath, attachmentsDir, !this.testOnly, this.overwriteDBContents, checkReferences);
			if (result.returnCode == ReturnCode.ERROR) {
				LoggingHelper.error().log(result.message);
			}
			else {
				LoggingHelper.info().log(result.message);
			}
		}
		else {
			LoggingHelper.warn().messageColor(Colors.ANSI_RED_BOLD).log("There is no argument given for template or workflow name");
		}
	}

	/**
	 * Import a template from the specified file path
	 * 
	 * @param templatePath Path to the template JSON file
	 * @param attachmentsDir Directory containing attachment files
	 * @param saveToDB Whether to save to database
	 * @param overwriteDB Whether to overwrite existing entries
	 * @param checkReferences Whether to check for existing references
	 * @return ImportResult with success status and message
	 */
	public ImportResult importTemplate(String templatePath, String attachmentsDir, boolean saveToDB, boolean overwriteDB, boolean checkReferences) {
		long startTime = System.currentTimeMillis();
		try {
			LoggingHelper.info().messageColor(Colors.ANSI_BLUE).log("Template Path: %s", templatePath);

			Object element = loadJsonElement(templatePath, "template");
			if (element == null) {
				return new ImportResult(ReturnCode.ERROR, "Failed to load template JSON", System.currentTimeMillis() - startTime);
			}

			if (!(element instanceof Template)) {
				return new ImportResult(ReturnCode.ERROR, "JSON file is not a valid Template definition", System.currentTimeMillis() - startTime);
			}

			Template template = (Template) element;

			// Skip import if template with this ID already exists in database (unless overwrite is enabled)
			if (containsTemplate(template.getId(), false)) {
				if (!overwriteDB) {
					LoggingHelper.warn().log("Template '%s' already exists in database - skipping import", template.getId());
					return new ImportResult(ReturnCode.ID_EXISTS, "Template already exists (use overwrite=true to replace)", System.currentTimeMillis() - startTime);
				}
				LoggingHelper.info().log("Template '%s' already exists in database - will be overwritten", template.getId());
			}

			// Check for existing references if protection is enabled and overwrite not active
			if (!overwriteDB && checkReferences) {
				LoggingHelper.info().log("Checking for existing template references...");
				if (isAnyTemplateReferenceContainedInDB(template)) {
					String msg = "Some program and/or attachment references of template '%s' found in database - import aborted";
					LoggingHelper.error().log(msg, template.getId());
					return new ImportResult(ReturnCode.ERROR, String.format(msg, template.getId()), System.currentTimeMillis() - startTime);
				}
			}

			// Rewrite the attachment path such that it is relative to the attachments-dir.
			rewriteAttachmentPaths(template.getProgram());
			this.jsonElementChecker.setAllCreationDates(template, Instant.now(), DEFAULT_IMPORT_USER);
			this.saveTemplate(template, !saveToDB, overwriteDB);
			// Model execution file must be copied to respective workspace
			Path srcPath = new FileSystemResource(templatePath).getFile().toPath().getParent();
			this.copyAttachmentsToWorkspace(srcPath, template.getProgram(), attachmentsDir);

			long duration = System.currentTimeMillis() - startTime;
			String message = String.format("Template '%s' imported successfully in %dms", template.getId(), duration);
			LoggingHelper.info().messageColor(Colors.ANSI_GREEN).log(message);
			return new ImportResult(ReturnCode.IMPORT_SUCCESS, message, duration);

		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			String message = "Template import failed: " + e.getMessage();
			LoggingHelper.error().log(message, e);
			return new ImportResult(ReturnCode.ERROR, message, duration);
		}
	}

	private void rewriteAttachmentPaths(Program program) {
		if (program != null && program.getAttachments() != null) {
			program.getAttachments().forEach(a -> {
				String attachmentPath = a.getPath();
				if (attachmentPath != null) {
					String filename = Paths.get(attachmentPath).getFileName().toString();
					String id = a.getId();
					String relPath = id + File.separator + filename;
					LoggingHelper.debug().log("Overwriting attachment path for attachment '%s' from '%s' to '%s'.",
							id, attachmentPath, relPath);
					a.setPath(relPath);
				}
			});
		}
	}

	private void copyAttachmentsToWorkspace(Path sourcePath, Program program, String attachmentsDir) {
		if (program != null && program.getAttachments() != null) {
			program.getAttachments().forEach(a -> {
				try {
					this.copyFileToWorkspaceDir(sourcePath, a.getPath(), a.getId(), attachmentsDir);
				} catch (IOException e) {
					LoggingHelper.warn().log("Failed to copy attachment file: %s", e.getMessage());
				}
			});
		}
	}

	/**
	 * Import a workflow from the specified file path
	 * 
	 * @param workflowPath Path to the workflow JSON file
	 * @param attachmentsDir Directory containing attachment files
	 * @param saveToDB Whether to save to database
	 * @param overwriteDB Whether to overwrite existing entries
	 * @param checkReferences Whether to check for existing references
	 * @return ImportResult with success status and message
	 */
	public ImportResult importWorkflow(String workflowPath, String attachmentsDir, boolean saveToDB, boolean overwriteDB, boolean checkReferences) {
		long startTime = System.currentTimeMillis();
		try {
			LoggingHelper.info().messageColor(Colors.ANSI_BLUE).log("Workflow Path: %s", workflowPath);

			Object element = loadJsonElement(workflowPath, "workflow");
			if (element == null) {
				return new ImportResult(ReturnCode.ERROR, "Failed to load workflow JSON", System.currentTimeMillis() - startTime);
			}

			if (!(element instanceof Workflow)) {
				return new ImportResult(ReturnCode.ERROR, "JSON file is not a valid Workflow definition", System.currentTimeMillis() - startTime);
			}

			Workflow workflow = (Workflow) element;

			// Skip import if workflow with this ID already exists in database (unless overwrite is enabled)
			if (containsWorkflow(workflow.getId(), false)) {
				if (!overwriteDB) {
					LoggingHelper.warn().log("Workflow '%s' already exists in database - skipping import", workflow.getId());
					return new ImportResult(ReturnCode.ID_EXISTS, "Workflow already exists (use overwrite=true to replace)", System.currentTimeMillis() - startTime);
				}
				LoggingHelper.info().log("Workflow '%s' already exists in database - will be overwritten", workflow.getId());
			}

			// Ensure the step-based config has a valid ID
			if (!checkStepBasedConfigId(workflow)) {
				return new ImportResult(ReturnCode.ERROR, "Invalid step-based config ID", System.currentTimeMillis() - startTime);
			}

			// Check for existing references if protection is enabled and overwrite not active
			if (!overwriteDB && checkReferences) {
				LoggingHelper.info().log("Checking for existing workflow references...");
				if (isAnyReferenceContainedInDB(workflow)) {
					String msg = "Some block and/or program references of workflow '%s' found in database - import aborted";
					LoggingHelper.error().log(msg, workflow.getId());
					return new ImportResult(ReturnCode.ERROR, String.format(msg, workflow.getId()), System.currentTimeMillis() - startTime);
				}
			}

			// Rewrite attachment paths for all workflow blocks
			for (Block workflowBlock : workflow.getBlocks().values()) {
				rewriteAttachmentPaths(workflowBlock.getProgram());
			}

			this.jsonElementChecker.setAllCreationDates(workflow, Instant.now(), DEFAULT_IMPORT_USER);
			this.saveWorkflow(workflow, !saveToDB, overwriteDB);

			// Copy attachment files for all workflow blocks
			Path srcPath = new FileSystemResource(workflowPath).getFile().toPath().getParent();
			for (Block workflowBlock : workflow.getBlocks().values()) {
				this.copyAttachmentsToWorkspace(srcPath, workflowBlock.getProgram(), attachmentsDir);
			}

			long duration = System.currentTimeMillis() - startTime;
			String message = String.format("Workflow '%s' imported successfully in %dms", workflow.getId(), duration);
			LoggingHelper.info().messageColor(Colors.ANSI_GREEN).log(message);
			return new ImportResult(ReturnCode.IMPORT_SUCCESS, message, duration);

		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			String message = "Workflow import failed: " + e.getMessage();
			LoggingHelper.error().log(message, e);
			return new ImportResult(ReturnCode.ERROR, message, duration);
		}
	}

	/**
	 * Import all templates and workflows from a directory
	 * 
	 * @param importPath Path to the directory containing template/workflow JSON files
	 * @param attachmentsDir Directory containing attachment files
	 * @param saveToDB Whether to save to database
	 * @param overwriteDB Whether to overwrite existing entries
	 * @param checkReferences Whether to check for existing references
	 * @return ImportResult with success status and summary message
	 */
	public ImportResult importFromPath(String importPath, String attachmentsDir, boolean saveToDB, boolean overwriteDB, boolean checkReferences) {
		long startTime = System.currentTimeMillis();
		try {
			LoggingHelper.info().messageColor(Colors.ANSI_BLUE).log("Import Path: %s", importPath);

			Path directory = Paths.get(importPath);
			if (!Files.exists(directory) || !Files.isDirectory(directory)) {
				return new ImportResult(ReturnCode.ERROR, "Import path does not exist or is not a directory: " + importPath, System.currentTimeMillis() - startTime);
			}

			// Find all JSON files in the directory
			List<Path> jsonFiles = Files.list(directory)
				.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".json"))
				.sorted()
				.toList();

			if (jsonFiles.isEmpty()) {
				return new ImportResult(ReturnCode.ERROR, "No JSON files found in import directory: " + importPath, System.currentTimeMillis() - startTime);
			}

			int successCount = 0;
			int failCount = 0;
			StringBuilder messages = new StringBuilder();

			LoggingHelper.info().log("Found %d JSON file(s) to import from directory", jsonFiles.size());

			for (Path jsonFile : jsonFiles) {
				String fileName = jsonFile.getFileName().toString();
				LoggingHelper.info().log("Processing file: %s", fileName);

				try {
					Object element = loadJsonElement(jsonFile.toString(), "element");
					if (element == null) {
						LoggingHelper.warn().log("Failed to load JSON element from %s", fileName);
						failCount++;
						messages.append("Failed to load ").append(fileName).append("; ");
						continue;
					}

					ImportResult result = null;
					if (element instanceof Template) {
						result = importTemplate(jsonFile.toString(), attachmentsDir, saveToDB, overwriteDB, checkReferences);
					} else if (element instanceof Workflow) {
						result = importWorkflow(jsonFile.toString(), attachmentsDir, saveToDB, overwriteDB, checkReferences);
					} else {
						LoggingHelper.warn().log("Unknown element type in file %s", fileName);
						failCount++;
						messages.append("Unknown type in ").append(fileName).append("; ");
						continue;
					}

					if (result.returnCode == ReturnCode.IMPORT_SUCCESS) {
						successCount++;
					} else {
						failCount++;
						messages.append(fileName).append(": ").append(result.message).append("; ");
					}
				} catch (Exception e) {
					LoggingHelper.error().log("Error processing file %s: %s", fileName, e.getMessage());
					failCount++;
					messages.append("Error in ").append(fileName).append(": ").append(e.getMessage()).append("; ");
				}
			}

			long duration = System.currentTimeMillis() - startTime;
			String message = String.format("Path import completed: %d succeeded, %d failed in %dms. %s", 
				successCount, failCount, duration, messages.toString());
			
			ReturnCode returnCode = failCount == 0 ? ReturnCode.IMPORT_SUCCESS : ReturnCode.ERROR;
			if (returnCode == ReturnCode.IMPORT_SUCCESS) {
				LoggingHelper.info().messageColor(Colors.ANSI_GREEN).log(message);
			} else {
				LoggingHelper.warn().log(message);
			}
			
			return new ImportResult(returnCode, message, duration);

		} catch (Exception e) {
			long duration = System.currentTimeMillis() - startTime;
			String message = "Path import failed: " + e.getMessage();
			LoggingHelper.error().log(message, e);
			return new ImportResult(ReturnCode.ERROR, message, duration);
		}
	}

	private void copyFileToWorkspaceDir(Path sourcePath, String attachmentPath, String attachmentId, String attachmentsDir) throws IOException {
        if (sourcePath == null) {
        	LoggingHelper.error().log("import directory is not given as argument!");
        	throw new NoSuchElementException("no import directory given for attachment '" + attachmentId + "' !");
        }
        
        if (attachmentPath == null || attachmentPath.isBlank()) {
            return;
        }
        
        String modelExecutionName = Paths.get(attachmentPath).getFileName().toString();
        Path sourceModelFile = Paths.get(sourcePath.toString(), modelExecutionName);
        
        // copy the model file to the attachmentsDir if it exists
        if (Files.exists(sourceModelFile)) {
        	Path targetDir = Paths.get(attachmentsDir != null ? attachmentsDir : "/proof/workspace/attachments");
        	Path targetModelFile = targetDir.resolve(attachmentId).resolve(modelExecutionName);
        	LoggingHelper.debug().log("Copying model file '%s' of attachment '%s' to attachmentsDir '%s'. Target path: %s ...",
        			modelExecutionName, attachmentId, attachmentsDir, targetModelFile);
        	Files.createDirectories(targetModelFile.getParent());
        	Files.copy(sourceModelFile, targetModelFile, StandardCopyOption.REPLACE_EXISTING);
        } else {
        	LoggingHelper.info().log("Model file '%s' of attachment '%s' does not exist in import path!", sourceModelFile, attachmentId);
        }
	}

	/**
	 * print the contents of the target database. This method is called when the command line argument '--printDBContents=true' is given
	 */
	private void printDatabaseContents() {
		final int[] num = new int[] { 1 };
		if (this.getBooleanArg(ARG_PRINT, false)) {

			System.out.println("\nContents of the Repository '" + PROOF_DB + "':");
			System.out.println("\nAll Workflows in the Repo:");
			this.printWorkflowsFromDB();
			System.out.println("\nAll Blocks in the Repo:");
			this.printBlocksFromDB();
			System.out.println("\nAll Programs in the Repo:");
			this.printProgramsFromDB();
			System.out.println("\nAll Attachments in the Repo:");
			this.printAttachmentsFromDB();

			System.out.println();
		}
	}

	private boolean getBooleanArg(String key, boolean defaultValue) {
		String arg = this.cliArguments.get(key);
		if (arg != null) {
			return Boolean.parseBoolean(arg);
		}
		else {
			return defaultValue;
		}
	}

	private final int[] savings = new int[5];


	private void saveWorkflow(Workflow workflow, boolean testOnly, boolean overwriteDBContents) {
		LoggingHelper.debug().log("start saving Workflow " + workflow.getId() );
		

		if (!containsWorkflow(workflow.getId(), false)) {
			LoggingHelper.info().log( (testOnly ? "TEST " : "" ) + "CREATING Workflow: " + workflow.getId());
			if (! testOnly) {
				this.configManagerService.createWorkflow(workflow);
			}
		} else if (overwriteDBContents) {
			if (! testOnly) {
				this.configManagerService.updateWorkflow(workflow);
			}
			LoggingHelper.info().log((testOnly ? "TEST " : "" ) + "UPDATING Workflow (OVERRIDE): " + workflow.getId());
			LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).log("\t--> Workflow '" + workflow.getId() + " already exists in the database ==> Workflow " + (testOnly ? "would be " : "") + "overridden!" );
		} else {		
			LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).withBorder().log("%s Workflow '%s' already exists in the " + 
				"database => Workflow not saved! (if desired, set the command line argument %s=true)", 
				(testOnly ? "(TEST) " : ""), workflow.getId(), ARG_OVERWRITE);	
		}
	}

	private void saveBlock(Block block, boolean testOnly, boolean overwriteDBContents) {
		LoggingHelper.info().log("saving Block " + block.getId() );
		if (!containsBlock(block.getId(), false)) {
			LoggingHelper.info().log( (testOnly ? "TEST " : "" ) + "CREATING Block: " + block.getId());
			if (! testOnly) {
				this.configManagerService.createBlock(block);
			}
		} else if (overwriteDBContents) {
			if (! testOnly) {
				this.configManagerService.updateBlock(block);
			}
			LoggingHelper.info().log((testOnly ? "TEST " : "" ) + "UPDATING Block (OVERRIDE): " + block.getId());
			LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).log("\t--> Block '" + block.getId() + " already exists in the database ==> Block " + (testOnly ? "would be " : "") + "overridden!" );
		} else {
			LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).withBorder().log((testOnly ? "(TEST) " : "") + " Block '" + block.getId()
					+ "' already exists in the database => Block not saved!  (if desired, set the command line argument " + ARG_OVERWRITE + "=true)");
		}
	}

	private void saveTemplate(Template template, boolean testOnly, boolean overwriteDBContents) {
		LoggingHelper.debug().log("start saving Template " + template.getId() );
		if( template.getColor() == null ) {
			template.setColor(DEFAULT_IMPORT_COLOR);
		}
		if (!containsTemplate(template.getId(), false)) {
			LoggingHelper.info().log( (testOnly ? "TEST " : "" ) + "CREATING Template: " + template.getId());
			if (! testOnly) {
				this.configManagerService.createTemplate(template);
			}
		} else if (overwriteDBContents) {
			if (! testOnly) {
				this.configManagerService.updateTemplate(template);
			}
			LoggingHelper.info().log((testOnly ? "TEST " : "" ) + "UPDATING Template (OVERRIDE): " + template.getId());
			LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).log("\t--> Template '" + template.getId() + " already exists in the database ==> Template " + (testOnly ? "would be " : "") + "overridden!" );
		} else {		
			LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).withBorder().log("%s Template '%s' already exists in the " + 
				"database => Template not saved! (if desired, set the command line argument %s=true)", 
				(testOnly ? "(TEST) " : ""), template.getId(), ARG_OVERWRITE);	
		}
	}

	/**
	 * check whether all the (sub) references of the imported workflows are available, either in the database or in imported workflow files
	 *
	 * @return true, if the references could be found, false otherwise
	 */
//	private boolean checkWorkflowConsistency() {
//		final int[] result = new int[1];
//		this.importedWorkflows.values().forEach(workflow -> {
//
//			LoggingHelper.info().log("Workflow '" + workflow.getId() + "' (File: '" + this.importedElementIdsAndFilenames.get(workflow.getId()) + "'):");
//			for (BlockDao wfb : workflow.getBlocks()) {
//				LoggingHelper.info().log("\t--> references Block: " + wfb.getId());
//			}
//			LoggingHelper.info().log("Checking references: ");
//			// first check the imported references
//			if (this.checkConsistencyOfBlockNames(workflow)) {
//				LoggingHelper.info().log("=> all references of workflow '" + workflow.getId() + "' found in import files or in the database ... \n");
//			}
//			else {
//				result[0]++;
//			}
//		});
//		return result[0] == 0;
//	}

	/**
	 * check whether the blocks referenced by a workflow are available, either in the database or in imported block files
	 *
	 * @param workflow
	 *            the workflow to be checked for references
	 * @return true, if the references could be found, false otherwise
	 */
// 	private boolean checkConsistencyOfBlockNames(WorkflowDao workflow) {
// 		for (BlockDao wfb : workflow.getBlocks()) {

// 			BlockDao block = this.importedBlocks.get(wfb.getId());

// 			if (block != null) {
// 				LoggingHelper.info().log("\tBlock '" + wfb.getId() + "' found in imported block list");
// 			} else {
// 				LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).log("\tBlock '" + wfb.getId() + "' not found in imported block list, looking for the block in the database ... => ");

// 				if( this.blockRepo.findById(block.getId()) != null ){
// 					LoggingHelper.info().log("\t\t--> references Program: " + block.getProgram().getId());
// 					if (this.importedPrograms.containsKey(block.getProgram().getId())) {
// 						LoggingHelper.info().log("\t\tProgram '" + block.getProgram().getId() + "' found in imported program list");
// 					}
// 					else if (this.programRepo.findById(block.getProgram().getId()) != null ) {

// //					else if (this.programRepository.findById(block.getProgram().getId()).isPresent()) {
// 						LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).log("\t\tProgram '" + block.getProgram().getId() + "' not found in imported program list but it already exists in the database => taking this one");
// 					} else {
// 						LoggingHelper.error().log("\t\tProgram '" + block.getProgram().getId() + "', referenced by Block '" + wfb.getId() + "' not found, neither in the imported program list, nor in the database ... => check the references, import aborted!");
// 						return false;
// 					}
// 				}
// 				return true;
// 			}

// //				Optional<BlockDao> optBlock = this.blockRepository.findById(wfb.getId());
// //				if (optBlock.isPresent()) {
// //					LoggingHelper.info().log("\tBlock '" + wfb.getId() + "' found in the database ... ");
// //					block = optBlock.get();
// //				} else {
// //					LoggingHelper.warn().messageColor(Colors.ANSI_RED).withBorder().log("Block '" + wfb.getId() + "' NOT found, neither in the imported block list, nor in the database ... => check the references, import aborted!");
// //					return false;
// //				}
// //			}
// //
// //			if (block != null) {
// //				LoggingHelper.info().log("\t\t--> references Program: " + block.getProgramId());
// //				if (this.importedPrograms.containsKey(block.getProgramId())) {
// //					LoggingHelper.info().log("\t\tProgram '" + block.getProgramId() + "' found in imported program list");
// //				} else if (this.programRepository.findById(block.getProgramId()).isPresent()) {
// //					LoggingHelper.warn().messageColor(Colors.ANSI_BLUE).log("\t\tProgram '" + block.getProgramId() + "' not found in imported program list but it already exists in the database => taking this one");
// //				} else {
// //					LoggingHelper.error().log("\t\tProgram '" + block.getProgramId() + "', referenced by Block '" + wfb.getId() + "' not found, neither in the imported program list, nor in the database ... => check the references, import aborted!");
// //					return false;
// //				}
// //			}
// 		}
// 		return true;
// 	}

	/**
	 * Check if a program and its attachments are already contained in the database.
	 * Shared utility for checking program references across templates and workflows.
	 *
	 * @param program the program to be checked for references
	 * @param contextId optional context identifier for logging (e.g., template/block ID)
	 * @return true, if the program or any of its attachments could be found in the database, false otherwise
	 */
	private boolean isAnyProgramReferenceContainedInDB(Program program, String contextId) {
		boolean contained = false;
		
		if (program != null) {
			String programId = program.getId();
			LoggingHelper.info().log("\t--> references Program: " + programId);
			if (programId == null) {
				LoggingHelper.error().log("\tProgram reference is null!");
				throw new IllegalArgumentException("Program reference is null (context: " + contextId + ")!");
			}
				
			if (containsProgram(programId, false)) {
				LoggingHelper.error().log("\tProgram '" + programId + "' found in the database. This is currently not supported!");
				contained = true;
			}

			// Check the attachments of the program
			if (program.getAttachments() != null) {
				List<Attachment> attachments = program.getAttachments();
				for (Attachment attachment : attachments) {
					if (containsAttachment(attachment.getId(), false)) {
						LoggingHelper.error().log("\tAttachment '%s' of Program '%s' found in the database. This is currently not supported!", attachment.getId(), programId);
						contained = true;
					}
				}
			}
		}
		return contained;
	}

	/**
	 * Check if the programs and attachments referenced by a template are already contained in the database.
	 * Used to prevent unintended overwrites when importing templates with existing references.
	 *
	 * @param template the template to be checked for references
	 * @return true, if one of the references could be found, false otherwise
	 */
	private boolean isAnyTemplateReferenceContainedInDB(Template template) {
		return isAnyProgramReferenceContainedInDB(template.getProgram(), "template: " + template.getId());
	}

	/**
	 * check if the blocks and programs referenced by a workflow are already contained in the database.
	 *
	 * @param workflow the workflow to be checked for references
	 * @return true, if one of the references could be found, false otherwise
	 */
	private boolean isAnyReferenceContainedInDB(Workflow workflow) {
		boolean contained = false;
		// Loop over all workflow blocks and check whether they are already contained in the database.
		for (Block workflowBlock : workflow.getBlocks().values()) {
			// Check block and db existence
			String blockId = workflowBlock != null ? workflowBlock.getId() : null;
			if (blockId == null) {
				LoggingHelper.error().log("\tBlock reference is null!");
				throw new IllegalArgumentException("Block reference of workflow '" + workflow.getId() + "' is null!");
			}

			if (containsBlock(blockId, false)) {
				LoggingHelper.error().log("\tBlock '" + blockId + "' found in the database. This is currently not supported!");
				contained = true;
			}

			// Check for the existence of the referenced template (via the id).
			// Remark: Warning only as the template is not used for the execution of the workflow.
			String templateId = workflowBlock.getTemplateId();
			if (templateId == null || !containsTemplate(templateId, contained)) {
				LoggingHelper.warn().log("\tTemplate reference '%s' of Block '%s' is null or not contained in db!", templateId, blockId);
			}

			// Check program and its attachments
			if (isAnyProgramReferenceContainedInDB(workflowBlock.getProgram(), "block: " + blockId)) {
				contained = true;
			}
		}
		return contained;
	}

	private void printAttachmentsFromDB()
    {
		try {
			this.configManagerService.getAttachments();
			List<Attachment> attachmentList =  this.configManagerService.getAttachments();
			if( attachmentList != null ) {
				if( attachmentList.size() == 0 ) {
					System.out.println("no attachments found!");
				}
				else {
					attachmentList.forEach(System.out::println);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
    }

	private void printProgramsFromDB()
	{
		try {
			List<Program> programList =  this.configManagerService.getPrograms();
			if( programList != null ) {
				if( programList.size() == 0 ) {
					System.out.println("no programs found!");
				}
				else {
					programList.forEach(System.out::println);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void printWorkflowsFromDB()
	{
		try {
			List<Workflow> wfList =  this.configManagerService.getWorkflows();
			if( wfList != null ) {
				if( wfList.size() == 0 ) {
					System.out.println("no workflows found!");
				}
				else {
					wfList.forEach(System.out::println);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void printBlocksFromDB()
	{
		try {
			List<Block> blockList =  this.configManagerService.getBlocks();
			if( blockList != null ) {
				if( blockList.size() == 0 ) {
					System.out.println("no blocks found!");
				}
				else {
					blockList.forEach(System.out::println);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Generic method to check if an entity exists in the database
	 * @param <T> The entity type (Workflow, Block, or Template)
	 * @param item The entity to check
	 * @param getter A function that retrieves the entity from the service
	 * @param entityType The name of the entity type for logging
	 * @param printError Whether to log errors if retrieval fails
	 * @return true if the entity exists, false otherwise
	 */
	private <T> boolean contains(String id, Function<String, T> getter, String entityType, boolean printError) {
		try {
			Optional<T> itemOpt = Optional.ofNullable(getter.apply(id));
			return itemOpt.isPresent();
		} catch (Exception e) {
			if (printError) {
				LoggingHelper.error().log("Error while trying to get %s from DB: %s", entityType, e.getMessage());
				e.printStackTrace();
			}
		}
		return false;
	}


	private boolean containsWorkflow(String workflowId, boolean printError) {
		return contains(workflowId, id -> this.configManagerService.getWorkflow(id), "workflow", printError);
	}


	private boolean containsStepBasedConfig(String stepBasedConfigId, boolean printError) {
		return contains(stepBasedConfigId, id -> this.configManagerService.getStepBasedConfiguration(id), "step based config", printError);
	}

	private boolean containsBlock(String blockId, boolean printError) {
		return contains(blockId, id -> this.configManagerService.getBlock(id), "block", printError);
	}

	private boolean containsTemplate(String templateId, boolean printError) {
		return contains(templateId, id -> this.configManagerService.getTemplate(id), "template", printError);
	}

	private boolean containsProgram(String programId, boolean printError) {
		return contains(programId, id -> this.configManagerService.getProgram(id), "program", printError);
	}

	private boolean containsAttachment(String attachmentId, boolean printError) {
		return contains(attachmentId, id -> this.configManagerService.getAttachment(id), "attachment", printError);
	}

}
