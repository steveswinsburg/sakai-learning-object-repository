package org.sakaiproject.content.repository.logic;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.apachecommons.CommonsLog;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.wicket.util.file.File;
import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.content.api.ContentResourceEdit;
import org.sakaiproject.content.repository.model.ContentItem;
import org.sakaiproject.content.repository.model.LearningObject;
import org.sakaiproject.content.repository.model.SearchItem;
import org.sakaiproject.content.repository.model.TechnicalRequirement;
import org.sakaiproject.content.repository.model.TechnicalRequirementList;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.event.api.NotificationService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.IdUsedException;
import org.sakaiproject.exception.InUseException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.TypeException;
import org.sakaiproject.search.api.InvalidSearchQueryException;
import org.sakaiproject.search.api.SearchList;
import org.sakaiproject.search.api.SearchResult;
import org.sakaiproject.search.api.SearchService;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;

/**
 * Logic class for our app.
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
@CommonsLog
public class ProjectLogic {

	@Getter @Setter
	private ToolManager toolManager;
	
	@Getter @Setter
	private SessionManager sessionManager;
	
	@Getter @Setter
	private UserDirectoryService userDirectoryService;
	
	@Getter @Setter
	private SecurityService securityService;
	
	@Getter @Setter
	private EventTrackingService eventTrackingService;
	
	@Getter @Setter
	private ServerConfigurationService serverConfigurationService;
	
	@Getter @Setter
	private SiteService siteService;
	
	@Getter @Setter
	private SearchService searchService;
	
	@Getter @Setter
	private ContentHostingService contentHostingService;
	
	
	/**
	 * init - perform any actions required here for when this bean starts up
	 */
	public void init() {
		log.info("init");
	}


	/**
	 * Get current siteid
	 * @return
	 */
	public String getCurrentSiteId(){
		return toolManager.getCurrentPlacement().getContext();
	}
	
	/**
	 * Get current user id
	 * @return
	 */
	public String getCurrentUserId() {
		return sessionManager.getCurrentSessionUserId();
	}
	
	/**
	 * Get current user display name
	 * @return
	 */
	public String getCurrentUserDisplayName() {
	   return userDirectoryService.getCurrentUser().getDisplayName();
	}
	
	/**
	 * Is the current user a superUser? (anyone in admin realm)
	 * @return
	 */
	public boolean isSuperUser() {
		return securityService.isSuperUser();
	}
	
	/**
	 * Post an event to Sakai
	 * 
	 * @param event			name of event
	 * @param reference		reference
	 * @param modify		true if something changed, false if just access
	 * 
	 */
	public void postEvent(String event,String reference,boolean modify) {
		eventTrackingService.post(eventTrackingService.newEvent(event,reference,modify));
	}
	
	
	/**
	 * Get a configuration parameter as a boolean
	 * 
	 * @param	dflt the default value if the param is not set
	 * @return
	 */
	public boolean getConfigParam(String param, boolean dflt) {
		return serverConfigurationService.getBoolean(param, dflt);
	}
	
	/**
	 * Get a configuration parameter as a String
	 * 
	 * @param	dflt the default value if the param is not set
	 * @return
	 */
	public String getConfigParam(String param, String dflt) {
		return serverConfigurationService.getString(param, dflt);
	}
	
	/**
	 * Perform a search
	 * @param s	The search string
	 * @return
	 */
	public List<SearchItem> performSearch(String s) {
		SearchList l = null;
		try {
			l = searchService.search(s, Collections.singletonList(getCurrentSiteId()), 0, 100);
		} catch (InvalidSearchQueryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		log.debug("Search results:" + l.size());
		
		List<SearchItem> items = new ArrayList<SearchItem>();
		
		Iterator iter = l.iterator();
		while(iter.hasNext()){
			SearchResult r = (SearchResult) iter.next();
			
			SearchItem si = new SearchItem();
			si.setTitle(r.getTitle());
			si.setUrl(r.getUrl());
			
			try {
				si.setPreview(r.getSearchResult());
			} catch (Exception e){
				//we dont care, it just wont be set
			}
			
			try {
				System.out.println("terms:" + Arrays.asList(r.getTerms().getTerms()));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			items.add(si);
			
		}
		
		return items;
		
	}
	
	/**
	 * Get a list of all resources in the current site.
	 * 
	 * Transforms from the Sakai ContentResource to an internal ContentItem
	 * 
	 * @param filter optional filter string for post processing of the list
	 * 
	 * @return
	 */
	public List<ContentItem> getResources(String filter) {
		
		List<ContentItem> items = new ArrayList<ContentItem>();
		
		String currentSiteCollectionId = contentHostingService.getSiteCollection(getCurrentSiteId());
		log.debug("currentSiteCollectionId: " + currentSiteCollectionId);
		
		List<ContentResource> resources = contentHostingService.getAllResources(currentSiteCollectionId);
				
		ContentResourceHelper helper = new ContentResourceHelper();
		
		for(ContentResource resource: resources) {
			
			//setup the helper for this resource item
			helper.setResource(resource);
			
			//first check it matches any filter, if set, otherwise skip.
			if(StringUtils.isNotBlank(filter)) {
				if (!helper.resourceMatchesFilter(filter)) {
					continue;
				}
			}
			
			ContentItem item = new ContentItem();
			item.setId(resource.getId());
			item.setSize(resource.getContentLength());
			item.setUrl(resource.getUrl());
			item.setMimeType(resource.getContentType());
			item.setTitle(helper.getTitle());
			item.setAuthor(helper.getCreator());
			item.setModifiedDate(helper.getModifiedDate());
			
						
			items.add(item);
		}
		
		
		return items;
	}

	/**
	 * Get the display name of a user
	 * 
	 * @param uuid 
	 * @return
	 */
	public String getUserDisplayName(String uuid) {
		try {
			return userDirectoryService.getUser(uuid).getDisplayName();
		} catch (UserNotDefinedException e){
			return uuid;
		}
	}
	
	/**
	 * Get a learning object
	 * @param resourceId - id of the resource in CHS
	 * @return
	 */
	public LearningObject getLearningObject(String resourceId) {
		
		if(StringUtils.isBlank(resourceId)) {
			log.error("Cannot retrieve resource, id was blank");
			return null;
		}
		
		//get the resource
		ContentResource resource = null;
		
		try {
      resource = contentHostingService.getResource(resourceId);
    } catch (IdUnusedException | TypeException | PermissionException e) {
      e.printStackTrace();
    }
		
		
		if(resource == null) {
			log.error("Cannot retrieve resource, an error occurred");
			return null;
		}
		
		LearningObject lo = convertResource(resource);
		return lo;
				
	}
	
	/**
	 * Add a new learning object to the repository. This should be called when all data is ready to be added.
	 * 
	 * @param lo
	 * @return LearningObject with ID field populated
	 */
	public boolean addNewLearningObject(LearningObject lo) {
				
		ContentResourceEdit resource = null;
		
		String currentSiteCollectionId = contentHostingService.getSiteCollection(getCurrentSiteId());
		String baseName = FilenameUtils.getBaseName(lo.getFilename());
		String extension = FilenameUtils.getExtension(lo.getFilename());
		
		byte[] bytes = retrieveStashedFile(lo.getStashedFilePath());
		if(bytes == null) {
			log.error("Stashed file could not be retrieved, aborting");
			return false;
		}
		
		SecurityAdvisor advisor = enableSecurityAdvisor();
		
		try {
				
			resource = contentHostingService.addResource(currentSiteCollectionId, baseName, extension, 100);
			resource.setContent(bytes);
			resource.setContentType(lo.getMimetype());
			
			//add standard properties
			ResourceProperties props = resource.getPropertiesEdit();
			props.addProperty(ResourceProperties.PROP_CONTENT_TYPE, lo.getMimetype());
			
			//set displayName as filename, or fall back to actual filename
			if(StringUtils.isNotBlank(lo.getDisplayName())){
				props.addProperty(ResourceProperties.PROP_DISPLAY_NAME, lo.getDisplayName());
			} else {
				props.addProperty(ResourceProperties.PROP_DISPLAY_NAME, lo.getFilename());
			}
			props.addProperty(ResourceProperties.PROP_CREATOR, getCurrentUserDisplayName());
						
			//add LO props
			addLearningObjectProperties(props, lo);
			
			log.error("PROPS!" + props.toString());
			
			contentHostingService.commitResource(resource, NotificationService.NOTI_NONE);
			return true;

		} catch (Exception e) {
			
			contentHostingService.cancelResource(resource);
			log.error("addNewLearningObject: failed: " + e.getClass() + " : " + e.getMessage());
			e.printStackTrace();
		} finally {
			disableSecurityAdvisor(advisor);
		}
		
		return false;
	}
	
	/**
	 * Update a learning object.
	 * @param lo the updated learning object data
	 * @return
	 */
	public boolean updateLearningObject(LearningObject updated) {
		
		if(updated == null) {
			log.error("LO was null, cannot update.");
			return false;
		}
		
		if(StringUtils.isBlank(updated.getResourceId())) {
			log.error("LO resourceId was null, cannot update.");
			return false;
		}
		
		String resourceId = updated.getResourceId();
		
		//get the original LO for the given resourceId
		LearningObject original = getLearningObject(resourceId);
		
		//serialise it
		String originalAsXml = XMLHelper.serialiseLearningObject(original);
		
		//get the ContentResourceEdit
		ContentResourceEdit resource = null;
		try {
	    resource = (ContentResourceEdit) contentHostingService.getResource(resourceId);
    } catch (IdUnusedException | TypeException | PermissionException e) {
	    e.printStackTrace();
	    return false;
    }
		
		//clear the old properties so we can set the new ones
		//dont remove VERSION or LO_HISTORY
		ResourceProperties props = resource.getPropertiesEdit();
		props.removeProperty(ResourceProperties.PROP_COPYRIGHT_CHOICE);
		props.removeProperty(ResourceProperties.PROP_COPYRIGHT);
		props.removeProperty(ResourceProperties.PROP_COPYRIGHT_ALERT);
		props.removeProperty(ResourceProperties.PROP_DESCRIPTION);
		props.removeProperty("FILE_STATUS");
		props.removeProperty("PUBLISHER");
		props.removeProperty("RESOURCE_TYPE");
		props.removeProperty("ENVIRONMENT");
		props.removeProperty("INTENDED_AUDIENCE");
		props.removeProperty("AUDIENCE_EDUCATION");
		props.removeProperty("ENGAGEMENT");
		props.removeProperty("INTERACTIVITY");
		props.removeProperty("DIFFICULTY");
		props.removeProperty("ASSUMED_KNOWLEDGE");
		props.removeProperty("LEARNING_TIME");
		props.removeProperty("KEYWORDS");
		props.removeProperty("OUTCOMES");
		props.removeProperty("LEARNING_TIME");
		props.removeProperty("TECH_REQ_TYPE");
		props.removeProperty("TECH_REQ_NAME");
		props.removeProperty("TECH_REQ_MIN_VERSION");
		props.removeProperty("TECH_REQ_MAX_VERSION");
		props.removeProperty("TECH_REQ_ANDOR");
		props.removeProperty("TECH_REQ_INSTALL_REMARKS");
		props.removeProperty("TECH_REQ_OTHER");
		props.removeProperty("TECH_REQ_XML");

		//increment version
		updated.setVersion(Integer.parseInt(props.getProperty("VERSION") + 1));
		
		//add the updated properties
		addLearningObjectProperties(props, updated);
		
		//add the serialised original LO so we can maintain history
		props.addPropertyToList("LO_HISTORY", originalAsXml);
		
		log.error("UPDATED PROPS!" + props.toString());
		
		//save it
		SecurityAdvisor advisor = enableSecurityAdvisor();
		try {
			contentHostingService.commitResource(resource, NotificationService.NOTI_NONE);
			return true;	
		} catch (Exception e) {
			contentHostingService.cancelResource(resource);
			log.error("updatedLearningObject: failed: " + e.getClass() + " : " + e.getMessage());
			e.printStackTrace();
		} finally {
			disableSecurityAdvisor(advisor);
		}
		
		return false;
	}
	
	/**
	 * Stashes a file in a temporary area on the filesystem, returns the filePath.
	 * 
	 * 
	 * @param bytes - byte[] for file
	 * @return path to file
	 */
	public String stashFile(byte[] bytes) {
		String outputPath = FileUtils.getTempDirectory().getAbsolutePath() + File.separatorChar + UUID.randomUUID().toString();
		File f = new File(outputPath);
		f.deleteOnExit();
		try {
			FileUtils.writeByteArrayToFile(f, bytes);
			log.debug("Stashed file: " + f);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		return outputPath;
	}
	
	/**
	 * Delete a resource from CHS
	 * 
	 * @param resourceId - the resourceId of the file to delete
	 * @return
	 */
	public boolean deleteResource(String resourceId) {
		
		if(StringUtils.isBlank(resourceId)) {
			log.error("Cannot delete resource, id was blank");
			return false;
		}
		
		try {
			contentHostingService.removeResource(resourceId);
			log.info("User: " + getCurrentUserId() + " removed resource: " + resourceId);
			return true;
		} catch (IdUnusedException e) {
			e.printStackTrace();
		} catch (TypeException e) {
			e.printStackTrace();
		} catch (InUseException e) {
			e.printStackTrace();
		} catch (PermissionException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	/**
	 * Complementary function for retrieving a stashed file from the filesystem
	 * @param stashedPath path to file
	 * @return byte[] for the file, null if it doesnt exist
	 */
	private byte[] retrieveStashedFile(String stashedPath) {
		
		if(StringUtils.isBlank(stashedPath)) {
			log.info("No stashed file, cannot retrieve!");
			return null;
		}
		
		File f = new File(stashedPath);
		if(!f.exists()) {
			return null;
		}
		try {
			log.info("Retrieving stashed file: " + stashedPath);
			return FileUtils.readFileToByteArray(f);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	/**
	 * Enable an advisor
	 * @return
	 */
	private SecurityAdvisor enableSecurityAdvisor() {
		SecurityAdvisor advisor = new SecurityAdvisor() {
            public SecurityAdvice isAllowed(String userId, String function, String reference) {
                return SecurityAdvice.ALLOWED;
            }
        };
        securityService.pushAdvisor(advisor);
        
        return advisor;

	}
	
	/**
	 * Disable the advisor
	 * @param advisor
	 */
	private void disableSecurityAdvisor(SecurityAdvisor advisor){
		securityService.popAdvisor(advisor);
	}
	
	/**
	 * Adds the props from the LO to the given resource properties
	 * @param p - the resource props to set the info into
	 * @param lo - the LO that has the info
	 * @return
	 */
	private void addLearningObjectProperties(ResourceProperties p, LearningObject lo) {
		
		//version
		p.addProperty("VERSION", Integer.toString(lo.getVersion()));
		
		//copyrightStatus
		p.addProperty(ResourceProperties.PROP_COPYRIGHT_CHOICE, lo.getCopyrightStatus());
		
		//copyrightCustomText
		p.addProperty(ResourceProperties.PROP_COPYRIGHT, lo.getCopyrightCustomText());
		
		//copyrightAlert
		p.addProperty(ResourceProperties.PROP_COPYRIGHT_ALERT, Boolean.toString(lo.isCopyrightAlert()));
		
		//access (TODO, need to set PubView and access etc)
		
		//dateFrom (TODO, need to set a Time object here, see if there is a way around it.
		//dateTo (TODO, need to set a Time object here, see if there is a way around it.
		
		//fileStatus
		p.addProperty("FILE_STATUS", lo.getFileStatus());
		
		//publisher
		p.addProperty("PUBLISHER", lo.getPublisher());
		
		//description
		p.addProperty(ResourceProperties.PROP_DESCRIPTION, lo.getDescription());
		
		//resourceType
		p.addProperty("RESOURCE_TYPE", lo.getResourceType());
		
		//environment
		p.addProperty("ENVIRONMENT", lo.getEnvironment());
		
		//intendedAudience
		p.addProperty("INTENDED_AUDIENCE", lo.getIntendedAudience());
		
		//audienceEducation
		p.addProperty("AUDIENCE_EDUCATION", lo.getAudienceEducation());
		
		//engagement
		p.addProperty("ENGAGEMENT", lo.getEngagement());
		
		//interactivity
		p.addProperty("INTERACTIVITY", lo.getInteractivity());
		
		//difficulty
		p.addProperty("DIFFICULTY", lo.getDifficulty());
		
		//assumedKnowledge
		p.addProperty("ASSUMED_KNOWLEDGE", lo.getAssumedKnowledge());
		
		//learningTime
		p.addProperty("LEARNING_TIME", lo.getLearningTime());
		
		//keywords
		p.addProperty("KEYWORDS", lo.getKeywords());
		
		//outcomes
		p.addProperty("OUTCOMES", lo.getOutcomes());
		
		//tech req (allows multiple, iterate over each and add a numbered set)

		//so that search works across all props we store the same attributes of each object into a properties list 
		//so that our object reconstructor works, we serialise the list of objects into xml and store that and then reconstruct it later. 
		//it is duplicated but it is required in order to keep the structure
		//we do not index the serialised field though.
		//in search:
		//customProperties.add("TSEARCH_INDEX.ACTUAL_PROPERTY_NAME"); ie
		//customProperties.add("Ttech_req_type.TECH_REQ_TYPE");
		for(TechnicalRequirement tr: lo.getTechReqs().getTechReqs()) {
			p.addPropertyToList("TECH_REQ_TYPE", tr.getTechReqType());
			p.addPropertyToList("TECH_REQ_NAME", tr.getTechReqName());
			p.addPropertyToList("TECH_REQ_MIN_VERSION", tr.getTechReqMinVersion());
			p.addPropertyToList("TECH_REQ_MAX_VERSION", tr.getTechReqMaxVersion());
			p.addPropertyToList("TECH_REQ_ANDOR", tr.getTechReqAndOr());
			p.addPropertyToList("TECH_REQ_INSTALL_REMARKS", tr.getTechReqInstallRemarks());
			p.addPropertyToList("TECH_REQ_OTHER", tr.getTechReqOther());			
			
		}
		
		//serialise whole object into a separate field so we can keep the structure, add to list since we can have multiples
		String xml = XMLHelper.serialiseTechReq(lo.getTechReqs());
		if(StringUtils.isNotBlank(xml)) {
			p.addPropertyToList("TECH_REQ_XML", xml);
		}
		
	}
	
	
	/**
	 * Take a ContentResource item and convert it to a LearningObject
	 * @param ContentResource
	 * @return
	 */
	private LearningObject convertResource(ContentResource resource) {
		
		//init our helper
		ContentResourceHelper helper = new ContentResourceHelper();
		helper.setResource(resource);
		
		//convert the resource into a learning object
		//take the basic params
		LearningObject lo = new LearningObject();
		lo.setResourceId(resource.getId());
		lo.setSize(resource.getContentLength());
		lo.setMimetype(resource.getContentType());
		lo.setDisplayName(helper.getTitle());
		
		lo.setDescription(helper.getDescription());
		
		//TODO convert the rest of the props here back into their object equivalents
		ResourceProperties props = resource.getProperties();
		
		if(NumberUtils.isNumber(props.getProperty("VERSION"))) {
			lo.setVersion(Integer.parseInt(props.getProperty("VERSION")));
		} else {
			lo.setVersion(0);
		}
		lo.setCopyrightStatus(props.getProperty(ResourceProperties.PROP_COPYRIGHT_CHOICE));
		lo.setCopyrightCustomText(props.getProperty(ResourceProperties.PROP_COPYRIGHT));
		lo.setCopyrightAlert(Boolean.parseBoolean(props.getProperty(ResourceProperties.PROP_COPYRIGHT_ALERT)));
		lo.setFileStatus(props.getProperty("FILE_STATUS"));
		lo.setPublisher(props.getProperty("PUBLISHER"));
		lo.setResourceType(props.getProperty("RESOURCE_TYPE"));
		lo.setEnvironment(props.getProperty("ENVIRONMENT"));
		lo.setIntendedAudience(props.getProperty("INTENDED_AUDIENCE"));
		lo.setAudienceEducation(props.getProperty("AUDIENCE_EDUCATION"));
		lo.setEngagement(props.getProperty("ENGAGEMENT"));
		lo.setInteractivity(props.getProperty("INTERACTIVITY"));
		lo.setDifficulty(props.getProperty("DIFFICULTY"));
		lo.setAssumedKnowledge(props.getProperty("ASSUMED_KNOWLEDGE"));
		lo.setLearningTime(props.getProperty("LEARNING_TIME"));
		lo.setKeywords(props.getProperty("KEYWORDS"));
		lo.setOutcomes(props.getProperty("OUTCOMES"));
		
		//deserialise the list of technical requirements
		TechnicalRequirementList techReqs = XMLHelper.deserialiseTechReqs(props.getProperty("TECH_REQ_XML"));
		lo.setTechReqs(techReqs);
		
		/*
		 
		
		
		//tech req (allows multiple, iterate over each and add a numbered set)

		//so that search works across all props we store the same attributes of each object into a properties list 
		//so that our object reconstructor works, we serialise the list of objects into xml and store that and then reconstruct it later. 
		//it is duplicated but it is required in order to keep the structure
		//we do not index the serialised field though.
		//in search:
		//customProperties.add("TSEARCH_INDEX.ACTUAL_PROPERTY_NAME"); ie
		//customProperties.add("Ttech_req_type.TECH_REQ_TYPE");
		for(TechnicalRequirement tr: lo.getTechReqs()) {
			p.addPropertyToList("TECH_REQ_TYPE", tr.getTechReqType());
			p.addPropertyToList("TECH_REQ_NAME", tr.getTechReqName());
			p.addPropertyToList("TECH_REQ_MIN_VERSION", tr.getTechReqMinVersion());
			p.addPropertyToList("TECH_REQ_MAX_VERSION", tr.getTechReqMaxVersion());
			p.addPropertyToList("TECH_REQ_ANDOR", tr.getTechReqAndOr());
			p.addPropertyToList("TECH_REQ_INSTALL_REMARKS", tr.getTechReqInstallRemarks());
			p.addPropertyToList("TECH_REQ_OTHER", tr.getTechReqOther());
			
			//serialise object into a separate field so we can keep the structure, add to list since we can have multiples
			String xml = XMLHelper.serialiseTechReq(tr);
			if(StringUtils.isNotBlank(xml)) {
				p.addPropertyToList("TECH_REQ_XML", xml);
			}
			
		}
		 
		 
		 */
		
		
		return lo;
	}
	
	
}
