/*
 * Copyright (c) 2025-2026
 * Karlsruhe Institute of Technology - Institute for Automation and Applied Informatics (IAI)
 */
package edu.kit.iai.webis.proofimporter;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import edu.kit.iai.webis.proofutils.wrapper.Workflow;
import edu.kit.iai.webis.proofutils.wrapper.Block;
import edu.kit.iai.webis.proofutils.wrapper.Program;
import edu.kit.iai.webis.proofutils.wrapper.Template;

import edu.kit.iai.webis.proofmodels.BlockDetail;
import edu.kit.iai.webis.proofmodels.TemplateDetail;
import edu.kit.iai.webis.proofmodels.TemplateDetail.InterfaceTypeEnum;
import edu.kit.iai.webis.proofmodels.WorkflowDetail;
import edu.kit.iai.webis.proofutils.Colors;
import edu.kit.iai.webis.proofutils.LoggingHelper;
import edu.kit.iai.webis.proofutils.model.InterfaceType;

@Service
public class JSONElementChecker {

//	private final static List<String> ignoredBlockArrayKeys = new ArrayList<>(Arrays.asList("metadata", "parameters", "context"));
//	private final static List<String> ignoredBlockObjectKeys = new ArrayList<>(Arrays.asList("mapping", "parameters"));
//	private final static List<String> ignoredWorkflowArrayKeys = new ArrayList<>(Arrays.asList("stepSizeDefinitions"));
//	private final static List<String> ignoredWorkflowObjectKeys = new ArrayList<>(Arrays.asList("mapping", "position"));

	private boolean errorOccured = false;

	private record ProofEntry(String name, JsonNodeType nodeType, Boolean required) {}


	/**
	 * a map containing all possible keywords and their type for a block that are checked for consistency.
	 */
	@SuppressWarnings("serial")
	private final HashMap<String, ProofEntry> JSONBlockFileKeyWords = new HashMap<String, ProofEntry>() {
		{
			this.put( "id", new ProofEntry("id", JsonNodeType.STRING, true ));
			this.put( "label", new ProofEntry("label", JsonNodeType.STRING, true ));
			this.put( "description", new ProofEntry("description", JsonNodeType.STRING, false ));
			this.put( "inputs", new ProofEntry("inputs", JsonNodeType.ARRAY, false ));
			this.put( "outputs", new ProofEntry("outputs", JsonNodeType.ARRAY, false ));
			this.put( "containerImage", new ProofEntry("containerImage", JsonNodeType.STRING, true ));
			this.put( "communicationParadigm", new ProofEntry("communicationParadigm", JsonNodeType.STRING, true ));

			// new
			this.put( "color", new ProofEntry("color", JsonNodeType.STRING, false) );  // optionale farbe setzen
			this.put( "textColor", new ProofEntry("textColor", JsonNodeType.STRING, false) );  // text color
			this.put( "program", new ProofEntry("program", JsonNodeType.OBJECT, true ));
			this.put( "blockType", new ProofEntry("blockType", JsonNodeType.STRING, true ));
			this.put( "modelVarName", new ProofEntry("modelVarName", JsonNodeType.STRING, true ));
//			this.put( "name", new ProofEntry("name", JsonNodeType.STRING, true ));
			this.put( "syncStrategy", new ProofEntry("syncStrategy", JsonNodeType.STRING, true ));
			this.put( "templateId", new ProofEntry("templateId", JsonNodeType.STRING, false));
			this.put( "templateName", new ProofEntry("templateName", JsonNodeType.STRING, false));
			this.put( "type", new ProofEntry("type", JsonNodeType.STRING, false));
			this.put( "phase", new ProofEntry("phase", JsonNodeType.STRING, false));
			this.put( "index", new ProofEntry("index", JsonNodeType.NUMBER, false));
			this.put( "shutdownRelevant", new ProofEntry("shutdownRelevant", JsonNodeType.STRING, false));

			this.put( "createdBy", new ProofEntry("createdBy", JsonNodeType.STRING, false ));   // optional
			this.put( "creationDate", new ProofEntry("creationDate", JsonNodeType.NUMBER, false ));   // optional
			this.put( "lastModifiedBy", new ProofEntry("lastModifiedBy", JsonNodeType.STRING, false ));   // optional
			this.put( "lastModifiedDate", new ProofEntry("lastModifiedDate", JsonNodeType.NUMBER, false ));   // optional

		}
	};

	/**
	 * a map containing all possible keywords and their type for a block that are checked for consistency.
	 */
	@SuppressWarnings("serial")
	private final HashMap<String, ProofEntry> JSONTemplateFileKeyWords = new HashMap<String, ProofEntry>() {
		{
			this.putAll(JSONElementChecker.this.JSONBlockFileKeyWords);
			this.put( "name", new ProofEntry("name", JsonNodeType.STRING, true ));
			this.remove("label");
		}
	};

	/**
	 * a map containing all possible keywords and their type for a workflow that are checked for consistency.
	 */
	@SuppressWarnings("serial")
	private final HashMap<String, ProofEntry> JSONWorkflowFileKeyWords = new HashMap<String, ProofEntry>() {
		{
			this.put( "id", new ProofEntry("id", JsonNodeType.STRING, true ));
			this.put( "label", new ProofEntry("label", JsonNodeType.STRING, true ));
			this.put( "description", new ProofEntry("description", JsonNodeType.STRING, false ));
			this.put( "blocks", new ProofEntry("blocks", JsonNodeType.ARRAY, true ));
			this.put( "communicationParadigm", new ProofEntry("communicationParadigm", JsonNodeType.STRING, true ));
			this.put( "simulationStrategy", new ProofEntry("simulationStrategy", JsonNodeType.STRING, false ));
			this.put( "stepBasedConfig", new ProofEntry("stepBasedConfig", JsonNodeType.OBJECT, false ));
			this.put( "connections", new ProofEntry("connections", JsonNodeType.ARRAY, true ));

			this.put( "createdBy", new ProofEntry("createdBy", JsonNodeType.STRING, false ));   // optional
			this.put( "creationDate", new ProofEntry("creationDate", JsonNodeType.NUMBER, false ));   // optional
			this.put( "lastModifiedBy", new ProofEntry("lastModifiedBy", JsonNodeType.STRING, false ));   // optional
			this.put( "lastModifiedDate", new ProofEntry("lastModifiedDate", JsonNodeType.NUMBER, false ));   // optional
		}
	};

	/**
	 * a map containing all possible keywords and their type for a program that are checked for consistency.
	 */
	@SuppressWarnings("serial")
	private final HashMap<String, ProofEntry> JSONProgramFileKeyWords = new HashMap<String, ProofEntry>() {
		{
			this.put( "id", new ProofEntry("id", JsonNodeType.STRING, true ));
			this.put( "label", new ProofEntry("label", JsonNodeType.STRING, true ));
			this.put( "description", new ProofEntry("description", JsonNodeType.STRING, false ));
			this.put( "tag", new ProofEntry("tag", JsonNodeType.STRING, false ));
			this.put( "runtime", new ProofEntry("runtime", JsonNodeType.STRING, true ));
			this.put( "entryPoint", new ProofEntry("entryPoint", JsonNodeType.STRING, true ));
			this.put( "attachments", new ProofEntry("attachments", JsonNodeType.STRING, true ));

			this.put( "createdBy", new ProofEntry("createdBy", JsonNodeType.STRING, false ));   // optional
			this.put( "creationDate", new ProofEntry("creationDate", JsonNodeType.NUMBER, false ));   // optional
			this.put( "lastModifiedBy", new ProofEntry("lastModifiedBy", JsonNodeType.STRING, false ));   // optional
			this.put( "lastModifiedDate", new ProofEntry("lastModifiedDate", JsonNodeType.NUMBER, false ));   // optional
		}
	};

	/**
	 * a map containing all possible keywords and their type for an attachment that are checked for consistency.
	 */
	@SuppressWarnings("serial")
	private final HashMap<String, ProofEntry> JSONAttachmentFileKeyWords = new HashMap<String, ProofEntry>() {
		{
			this.put( "id", new ProofEntry("id", JsonNodeType.STRING, true ));
			this.put( "label", new ProofEntry("label", JsonNodeType.STRING, true ));
			this.put( "path", new ProofEntry("path", JsonNodeType.STRING, true ));
			this.put( "description", new ProofEntry("description", JsonNodeType.STRING, false ));
			this.put( "path", new ProofEntry("path", JsonNodeType.STRING, true ));

			this.put( "createdBy", new ProofEntry("createdBy", JsonNodeType.STRING, false ));   // optional
			this.put( "creationDate", new ProofEntry("creationDate", JsonNodeType.NUMBER, false ));   // optional
			this.put( "lastModifiedBy", new ProofEntry("lastModifiedBy", JsonNodeType.STRING, false ));   // optional
			this.put( "lastModifiedDate", new ProofEntry("lastModifiedDate", JsonNodeType.NUMBER, false ));   // optional
		}
	};

	/**
	 * a map containing all possible keywords and their type for an input that are checked for consistency.
	 */
	@SuppressWarnings("serial")
	private final HashMap<String, ProofEntry> JSONInputFileKeyWords = new HashMap<String, ProofEntry>() {
		{
			this.put( "id", new ProofEntry("id", JsonNodeType.STRING, false ));
			this.put( "label", new ProofEntry("label", JsonNodeType.STRING, true ));
			this.put( "phase", new ProofEntry("phase", JsonNodeType.STRING, true ));
			this.put( "description", new ProofEntry("description", JsonNodeType.STRING, false ));
			this.put( "required", new ProofEntry("required", JsonNodeType.BOOLEAN, true ));
			this.put( "type", new ProofEntry("type", JsonNodeType.STRING, true ));
			this.put( "unit", new ProofEntry("unit", JsonNodeType.STRING, false ));
			this.put( "modelVarName", new ProofEntry("modelVarName", JsonNodeType.STRING, true ));
			this.put( "communicationType", new ProofEntry("communicationType", JsonNodeType.STRING, true ));
			// Remark: Default value is stored as string in the DB.
			this.put( "defaultValue", new ProofEntry("defaultValue", JsonNodeType.STRING, true ));
//			this.put( "metadata", new ProofEntry("metadata", JsonNodeType.OBJECT, false ));

			this.put( "createdBy", new ProofEntry("createdBy", JsonNodeType.STRING, false ));   // optional
			this.put( "creationDate", new ProofEntry("creationDate", JsonNodeType.NUMBER, false ));   // optional
			this.put( "lastModifiedBy", new ProofEntry("lastModifiedBy", JsonNodeType.STRING, false ));   // optional
			this.put( "lastModifiedDate", new ProofEntry("lastModifiedDate", JsonNodeType.NUMBER, false ));   // optional
		}
	};

	/**
	 * a map containing all possible keywords and their type for an output that are checked for consistency.
	 */
	@SuppressWarnings("serial")
	private final HashMap<String, ProofEntry> JSONOutputFileKeyWords = new HashMap<String, ProofEntry>() {
		{
			this.put( "id", new ProofEntry("id", JsonNodeType.STRING, false ));
			this.put( "label", new ProofEntry("label", JsonNodeType.STRING, true ));
			this.put( "phase", new ProofEntry("phase", JsonNodeType.STRING, true ));
			this.put( "description", new ProofEntry("description", JsonNodeType.STRING, false ));
			this.put( "type", new ProofEntry("type", JsonNodeType.STRING, true ));
			this.put( "unit", new ProofEntry("unit", JsonNodeType.STRING, false ));
			this.put( "modelVarName", new ProofEntry("modelVarName", JsonNodeType.STRING, true ));
			this.put( "communicationType", new ProofEntry("communicationType", JsonNodeType.STRING, true ));
//			this.put( "metadata", new ProofEntry("metadata", JsonNodeType.OBJECT, false ));

			this.put( "createdBy", new ProofEntry("createdBy", JsonNodeType.STRING, false ));   // optional
			this.put( "creationDate", new ProofEntry("creationDate", JsonNodeType.NUMBER, false ));   // optional
			this.put( "lastModifiedBy", new ProofEntry("lastModifiedBy", JsonNodeType.STRING, false ));   // optional
			this.put( "lastModifiedDate", new ProofEntry("lastModifiedDate", JsonNodeType.NUMBER, false ));   // optional
		}
	};

	/**
	 * load a PROOF JSON file and check the contents for syntax and some semantics.
	 *
	 * @param <T>
	 * @param file
	 *            the path to the JSON file, may be for a {@link Block}, a {@link Workflow}, or a {@link Program}
	 * @return a POJO of the object represented by the JSON file or null, if a POJO could not be created.
	 */
	public <T> T loadAndCheckJsonFile( final Path file ) throws PojoCreationException, WrongKeywordException {

		try( FileReader reader = new FileReader(file.toFile()) ) {
			LoggingHelper.info().log("=> checking file type for " + file.toFile().getName());

			ObjectMapper mapper = new ObjectMapper();
			mapper.registerModule(new JavaTimeModule());
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			JsonNode rootNode = mapper.readTree(reader);

			boolean isBlock = rootNode.has("index") || rootNode.has("templateId")  || rootNode.has("templateName");
			boolean hasInOutputs = rootNode.has("inputs") || rootNode.has("outputs");
			boolean isWorkflow = rootNode.has("blocks") && rootNode.has("connections");
			if( isBlock ) {
				LoggingHelper.warn().messageColor(Colors.ANSI_RED_BOLD).log("Trying to import an block. But Only the " +
					"import of Templates is supported in the first Version  => ignored\n");
				return null;
			}
			if( hasInOutputs ) {

				System.out.println("\nChecking Template\n=================");
				String blockID = "unknown";
				boolean hasIndex = false;;
				boolean hasTemplateId = false;;
				boolean hasTemplateName = false;;
//				boolean hasName = rootNode.findValue("name") != null;
				Map.Entry<String, JsonNode> programEntry = null;
				Map.Entry<String, JsonNode> inputsEntry = null;
				Map.Entry<String, JsonNode> outputsEntry = null;

				Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
				while (fields.hasNext()) {
				    Map.Entry<String, JsonNode> entry = fields.next();
				    String fieldName = entry.getKey();
				    JsonNode value = entry.getValue();

				    switch (fieldName) {
						case "id" -> blockID = value.asText();
						case "program" -> programEntry = entry;
						case "inputs" -> inputsEntry = entry;
						case "outputs" -> outputsEntry = entry;
						case "index" -> hasIndex = true;
						case "templateId" -> hasTemplateId = true;
						case "templateName" -> hasTemplateName = true;
					}

				    System.out.println(fieldName + " = " + value);
				    this.checkFieldKeyword(entry, this.JSONTemplateFileKeyWords );
//				    if( hasName ) {
//				    	checkFieldKeyword(entry, JSONTemplateFileKeyWords );
//				    } else {
//						checkFieldKeyword(entry, JSONBlockFileKeyWords );
//					}

				}
				if( this.errorMessages.size() > 0 ) {
					this.printErrorMessages("\n\nErrors occured while checking block '" + blockID + "':");
				}
				this.checkAllInputs( inputsEntry );
				this.checkAllOutputs( outputsEntry );
				this.checkProgram( programEntry );

				if( this.errorOccured ) {
					LoggingHelper.error().log("error checking file '" + file.toFile().getName() + "'");
					return null;
				}

				if( hasIndex || ( hasTemplateId && hasTemplateName) ) {

//					suche template in der Datenbank
					BlockDetail blockDetail = this.checkPojoCreation(mapper, rootNode, BlockDetail.class);
					Block block = new Block(blockDetail);
					if( blockDetail.getTemplateId() == null || blockDetail.getTemplateId() == null) {
						LoggingHelper.error().log("error checking file '" + file.toFile().getName()
								+ "'! The block seems to be a template because it has no reference to an existing template in the database ('templateID' and 'templateName')"
								+ "\nTry to import it as a template");
						return null;
					}
					//this.setAllCreationDates(block, Instant.now());

					if( block.getInterfaceType() == null ) {
						block.setInterfaceType(InterfaceType.STDIO);
					}
					return (T) block;
				}
				else {
					TemplateDetail templateDetail = this.checkPojoCreation(mapper, rootNode, TemplateDetail.class);
					Template template = new Template(templateDetail);
					//this.setAllCreationDates(template, Instant.now());
					if( template.getInterfaceType() == null ) {
						template.setInterfaceType(InterfaceTypeEnum.STDIO);
					}
					return (T) template;
				}
			}
			else if( isWorkflow ) {
				System.out.println("\nChecking Workflow\n=================");
				String workflowID = "unknown";

				Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
				while (fields.hasNext()) {
				    Map.Entry<String, JsonNode> entry = fields.next();
				    String fieldName = entry.getKey();
				    JsonNode value = entry.getValue();

				    if( fieldName.equals("id") ) {
				    	workflowID = value.asText();
				    }

				    System.out.println(fieldName + " = " + value);
				    this.checkFieldKeyword(entry, this.JSONWorkflowFileKeyWords );
				}

				if( this.errorMessages.size() > 0 ) {
					this.printErrorMessages("\n\nErrors occured while checking workflow '" + workflowID + "':");
				}

				if( this.errorOccured ) {
					LoggingHelper.error().log("error checking file '" + file.toFile().getName() + "'");
					return null;
				}

				WorkflowDetail workflowDetail = this.checkPojoCreation(mapper, rootNode, WorkflowDetail.class);
				Workflow workflow = new Workflow(workflowDetail);
				//this.setAllCreationDates(workflow, Instant.now());
				return (T) workflow;
			}
		}
		catch( JsonSyntaxException | JsonIOException | IOException e ) {
			LoggingHelper.error().log("error checking file " + file.toFile().getName() + ",  reason: \n" + e.getMessage());
		}
		catch( PojoCreationException pce ) {
			LoggingHelper.error().withBorder().log(pce.getMessage());
			throw pce;
		}

		return null;
	}

	private void setAllKeys(JsonNode node, String key, JsonNode newValue) {

	    if (node.isObject()) {
	        ObjectNode obj = (ObjectNode) node;

	        if (obj.has(key)) {
	            obj.set(key, newValue);
	        }

	        obj.fields().forEachRemaining(entry ->
	            this.setAllKeys(entry.getValue(), key, newValue)
	        );

	    } else if (node.isArray()) {
	        for (JsonNode element : node) {
	            this.setAllKeys(element, key, newValue);
	        }
	    }
	}

	public void setAllCreationDates(Workflow workflow, Instant instant, String user) {
		LoggingHelper.debug().log("Dates and User for workflow '%s':\nCreated: '%s' by %s,\nmodified: '%s' by %s", 
			workflow.getName(), workflow.getCreationDate(), workflow.getCreatedBy(), workflow.getLastModifiedDate(), 
			workflow.getLastModifiedBy());
		if (workflow.getCreationDate() == null) {
			LoggingHelper.debug().log("setting creation date for workflow '%s' to '%s'", workflow.getId(), instant);
			workflow.setCreationDate(instant);
		}
		if (workflow.getLastModifiedDate() == null || workflow.getLastModifiedDate().isBefore(workflow.getCreationDate())) {
			LoggingHelper.debug().log("setting last modified date for workflow '%s' to '%s'", workflow.getId(), instant);
			workflow.setLastModifiedDate(instant);
		}
		if (workflow.getCreatedBy() == null) {
			LoggingHelper.debug().log("setting creation and modified by for workflow '%s' to '%s'", workflow.getId(), user);
			workflow.setCreatedBy(user);
			workflow.setLastModifiedBy(user);
		}
		if (workflow.getLastModifiedBy() == null) {
			LoggingHelper.debug().log("setting modified by for workflow '%s' to '%s'", workflow.getId(), user);
			workflow.setLastModifiedBy(user);
		}

		workflow.getBlocks().forEach(
			 (id, b) -> { this.setAllCreationDates(b, instant, user); }
		);
	}

	public void setAllCreationDates(Block block, Instant instant, String user) {
		LoggingHelper.debug().log("Dates and User for block '%s':\nCreated: '%s' by %s,\nmodified: '%s' by %s", 
			block.getName(), block.getCreationDate(), block.getCreatedBy(), block.getLastModifiedDate(), 
			block.getLastModifiedBy());

		if (block.getCreationDate() == null) {
			block.setCreationDate(instant);
		}
		if (block.getLastModifiedDate() == null || block.getLastModifiedDate().isBefore(block.getCreationDate())) {
			block.setLastModifiedDate(instant);
		}	
		if (block.getCreatedBy() == null) {
			block.setCreatedBy(user);
			block.setLastModifiedBy(user);
		}
		if (block.getLastModifiedBy() == null) {
			block.setLastModifiedBy(user);
		}
		Program program = block.getProgram();
		LoggingHelper.debug().log("Dates and User for program '%s':\nCreated: '%s' by %s,\nmodified: '%s' by %s", 
			program.getId(), program.getCreationDate(), program.getCreatedBy(), program.getLastModifiedDate(), 
			program.getLastModifiedBy());

		if (program.getCreationDate() == null) {
			LoggingHelper.debug().log("setting creation date for program '%s' to '%s'", program.getId(), instant);
			program.setCreationDate(instant);
		}
		if (program.getLastModifiedDate() == null || program.getLastModifiedDate().isBefore(program.getCreationDate())) {
			LoggingHelper.debug().log("setting last modified date for program '%s' to '%s'", program.getId(), instant);	
			program.setLastModifiedDate(instant);
		}	
		if (program.getCreatedBy() == null) {
			LoggingHelper.debug().log("setting created by for program '%s' to '%s'", program.getId(), user);
			program.setCreatedBy(user);
			program.setLastModifiedBy(user);
		}
		if (program.getLastModifiedBy() == null) {
			LoggingHelper.debug().log("setting last modified by for program '%s' to '%s'", program.getId(), user);
			program.setLastModifiedBy(user);
		}
		program.getAttachments().forEach(
			a -> {
				if (a.getCreationDate() == null) {
					a.setCreationDate(instant);
				}
				if (a.getLastModifiedDate() == null || a.getLastModifiedDate().isBefore(a.getCreationDate())) {
					a.setLastModifiedDate(instant);
				}
				if (a.getCreatedBy() == null) {
					a.setCreatedBy(user);
					a.setLastModifiedBy(user);
				}
				if (a.getLastModifiedBy() == null) {
					a.setLastModifiedBy(user);
				}
			}
		);
		block.getInputs().forEach(
			(id, i) -> {
				if (i.getCreationDate() == null) {
					i.setCreationDate(instant);
				}
				if (i.getLastModifiedDate() == null || i.getLastModifiedDate().isBefore(i.getCreationDate())) {
					i.setLastModifiedDate(instant);
				}
				if (i.getCreatedBy() == null) {
					i.setCreatedBy(user);
					i.setLastModifiedBy(user);
				}
				if (i.getLastModifiedBy() == null) {
					i.setLastModifiedBy(user);
				}
			}
		);		
		block.getOutputs().forEach(
			(id, o) -> {
				if (o.getCreationDate() == null) {
					o.setCreationDate(instant);
				}
				if (o.getLastModifiedDate() == null || o.getLastModifiedDate().isBefore(o.getCreationDate())) {
					o.setLastModifiedDate(instant);
				}
				if (o.getCreatedBy() == null) {
					o.setCreatedBy(user);
					o.setLastModifiedBy(user);
				}
				if (o.getLastModifiedBy() == null) {
					o.setLastModifiedBy(user);
				}
			}
		);
	}

	public void setAllCreationDates(Template template, Instant instant, String user) {
		if (template.getCreationDate() == null) {
			template.setCreationDate(instant);
		}
		if (template.getCreatedBy() == null) {
			template.setCreatedBy(user);
			template.setLastModifiedBy(user);
		}
		if (template.getLastModifiedBy() == null) {
			template.setLastModifiedBy(user);
		}
		Program program = template.getProgram();
		if (program.getCreationDate() == null) {
			program.setCreationDate(instant);
		}
		if (program.getCreatedBy() == null) {
			program.setCreatedBy(user);
			program.setLastModifiedBy(user);
		}
		if (program.getLastModifiedBy() == null) {
			program.setLastModifiedBy(user);
		}
		program.getAttachments().forEach(
			a -> {
				if (a.getCreationDate() == null) {
					a.setCreationDate(instant);
				}
				if (a.getLastModifiedDate() == null || a.getLastModifiedDate().isBefore(a.getCreationDate())) {
					a.setLastModifiedDate(instant);
				}
				if (a.getCreatedBy() == null) {
					a.setCreatedBy(user);
					a.setLastModifiedBy(user);
				}
				if (a.getLastModifiedBy() == null) {
					a.setLastModifiedBy(user);
				}
			}
		);
	}

	private void checkAttachment(JsonNode attachmentNode) {
		System.out.println("\nchecking Attachment\n===================");
		if( attachmentNode.isObject() ) {
			String attachmentId = "unknown";
			Iterator<Map.Entry<String, JsonNode>> fields = attachmentNode.fields();

			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> pEntry = fields.next();
				String fieldName = pEntry.getKey();
				JsonNode value = pEntry.getValue();
				if( fieldName.equals("id") && value.isTextual() ) {
					attachmentId = value.asText();
				}

				System.out.println("checkAttachment: '" + fieldName + "' = " + value);

				this.checkFieldKeyword(pEntry, this.JSONAttachmentFileKeyWords );
			}
			this.printErrorMessages("\n\nErrors occured while checking attachment '" + attachmentId + "':");
		}
		else {
			System.out.println("input must be an object");
		}
	}

	private void checkAllAttachments(Entry<String, JsonNode> entry) {
		System.out.println("\nchecking Attachments ...\n========================");
		if( entry.getValue().isArray() ) {
			for (JsonNode element : entry.getValue()) {
				System.out.println("Element:" + element);
				this.checkAttachment(element);
			}
		}
		else {
			System.out.println("inputs must be an array");
		}
	}

	private void checkAllInputs(Entry<String, JsonNode> entry) {
		System.out.println("\nchecking Inputs ...\n=================");
		if( entry.getValue().isArray() ) {
			for (JsonNode element : entry.getValue()) {
			    System.out.println("Element:" + element);
			    this.checkInput(element);
			}
		}
		else {
			System.out.println("inputs must be an array");
		}
	}

	private void checkAllOutputs(Entry<String, JsonNode> entry) {
		System.out.println("\nchecking Outputs ...\n====================");
		if( entry.getValue().isArray() ) {
			for (JsonNode element : entry.getValue()) {
				System.out.println("Element:" + element);
				this.checkOutput(element);
			}
		}
		else {
			System.out.println("outputs must be an array");
		}
	}

	private void checkInput(JsonNode inputNode) {
		System.out.println("\nchecking Input ...");
		if( inputNode.isObject() ) {
			String inputLabel = "unknown";
			Iterator<Map.Entry<String, JsonNode>> fields = inputNode.fields();

			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> pEntry = fields.next();
				String fieldName = pEntry.getKey();
				JsonNode value = pEntry.getValue();
				if( fieldName.equals("label") && value.isTextual() ) {
					inputLabel = value.asText();
				}

				System.out.println("checkInput: '" + fieldName + "' = " + value);

				this.checkFieldKeyword(pEntry, this.JSONInputFileKeyWords );
			}
			this.printErrorMessages("\n\nErrors occured while checking input '" + inputLabel + "':");
		}
		else {
			System.out.println("input must be an object");
		}
	}

	private void checkOutput(JsonNode outputNode) {
		System.out.println("\nchecking Output ...");
		if( outputNode.isObject() ) {
			String outputLabel = "unknown";
			Iterator<Map.Entry<String, JsonNode>> fields = outputNode.fields();

			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> pEntry = fields.next();
				String fieldName = pEntry.getKey();
				JsonNode value = pEntry.getValue();
				if( fieldName.equals("label") && value.isTextual() ) {
					outputLabel = value.asText();
				}

				System.out.println("checkOutput: '" + fieldName + "' = " + value);

				this.checkFieldKeyword(pEntry, this.JSONOutputFileKeyWords );
			}
			this.printErrorMessages("\n\nErrors occured while checking output '" + outputLabel + "':");
		}
		else {
			System.out.println("output must be an object");
		}
	}

	private void checkProgram(Entry<String, JsonNode> entry) {
		System.out.println("\nchecking Program ...\n====================");
		if( entry.getValue().isObject() ) {
			String progId = "unknown";
			Iterator<Map.Entry<String, JsonNode>> fields = entry.getValue().fields();
			Map.Entry<String, JsonNode> attachmentsEntry = null;
			while (fields.hasNext()) {
			    Map.Entry<String, JsonNode> pEntry = fields.next();
			    String fieldName = pEntry.getKey();
			    JsonNode value = pEntry.getValue();
			    if( fieldName.equals("id") && value.isTextual() ) {
			    	progId = value.asText();
			    }

			    System.out.println("checkProg: '" + fieldName + "' = " + value);

			    if( fieldName.equals("attachments") && value.isArray() ) {
			    	this.checkAllAttachments( pEntry );
			    }
			    else {
			    	this.checkFieldKeyword(pEntry, this.JSONProgramFileKeyWords );
			    }
			}
			this.printErrorMessages("\n\nErrors occured while checking program '" + progId + "':");
		}
	}

	private void printErrorMessages( String headline ) {
		if( this.errorMessages.size() > 0 ) {
			this.errorOccured  = true;
			LoggingHelper.error().log(headline);

			this.errorMessages.forEach(m -> {
				LoggingHelper.error().log(m);
			});

			this.errorMessages.clear();
		}
	}

	private List<String> errorMessages = new ArrayList<String>();

	private boolean checkFieldKeyword(Map.Entry<String, JsonNode> field, HashMap<String, ProofEntry> validRecords) {
		ProofEntry pEntry = validRecords.get(field.getKey());
		if( pEntry == null ) {
			LoggingHelper.error().log("Key '%s' not found in keyword list", field.getKey());
			this.errorMessages.add("Key '" + field.getKey() + "' not found in keyword list");
			return false;
		}
		JsonNodeType nType = pEntry.nodeType;
		LoggingHelper.debug().log("Key '%s' found in keyword list and is %s", field.getKey(), (pEntry.required ? "required" : "optional"));
		
		// Allow NULL values for optional fields
		if( field.getValue().isNull() ) {
			if( pEntry.required ) {
				LoggingHelper.error().log("Key '%s' cannot be null because it is required", field.getKey());
				this.errorMessages.add("Key '" + field.getKey() + "' cannot be null because it is required");
				return false;
			}
			else {
				LoggingHelper.debug().log("Key '%s' is null but optional, allowing", field.getKey());
				return true;
			}
		}
		
		if( field.getValue().getNodeType() == nType ) {
			LoggingHelper.debug().log("Key '%s' has the correct JsonNodeType (%s)", field.getKey(), nType.toString());
			return true;
		}
		else {
			LoggingHelper.error().log("Key '%s' has an incorrect JsonNodeType (%s), expected Type is %s", field.getKey(), field.getValue().getNodeType(), nType);
			this.errorMessages.add("Key '%s' has an incorrect JsonNodeType (%s), expected Type is %s".formatted(field.getKey(), field.getValue().getNodeType(), nType));
			return false;
		}

	}

	@SuppressWarnings("unchecked")
	private <T> T checkPojoCreation( ObjectMapper mapper, JsonNode node, Class<?> clazz ) throws PojoCreationException {
		try {
			return (T) mapper.treeToValue(node, clazz);
		}
		catch( IllegalArgumentException | IOException e ) {
			LoggingHelper.error().withBorder().log("error creating POJO for class " + clazz.getSimpleName() + ",  reason: " + e.getMessage());
			throw new PojoCreationException(e.getMessage());
		}
	}

	private void checkFieldValue( Entry<String, JsonNode> field, HashMap<String, JsonNodeType> jsonprogramfilekeywords2 ) {
		if( field.getValue().isArray() && jsonprogramfilekeywords2.get(field.getKey()) == JsonNodeType.ARRAY
				|| field.getValue().isInt() && jsonprogramfilekeywords2.get(field.getKey()) == JsonNodeType.NUMBER
				|| field.getValue().isObject() && jsonprogramfilekeywords2.get(field.getKey()) == JsonNodeType.OBJECT
				|| field.getValue().isTextual() && jsonprogramfilekeywords2.get(field.getKey()) == JsonNodeType.STRING
				|| field.getValue().isBoolean() && jsonprogramfilekeywords2.get(field.getKey()) == JsonNodeType.BOOLEAN
				|| field.getValue().isDouble() && jsonprogramfilekeywords2.get(field.getKey()) == JsonNodeType.NUMBER ) {
			return;
		}
		else if( jsonprogramfilekeywords2.get(field.getKey()) != null ) {
			LoggingHelper.error().withBorder().log("Wrong type for field '" + field.getKey() + "'!  It is " + field.getValue().getNodeType() + " but should be " + jsonprogramfilekeywords2.get(field.getKey()));
		}
	}

}
