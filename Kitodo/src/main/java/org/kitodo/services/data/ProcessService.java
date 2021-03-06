/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.kitodo.services.data;

import de.sub.goobi.config.ConfigCore;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.InvalidImagesException;
import de.sub.goobi.metadaten.MetadataLock;
import de.sub.goobi.metadaten.MetadatenHelper;
import de.sub.goobi.metadaten.copier.CopierData;
import de.sub.goobi.metadaten.copier.DataCopier;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import javax.xml.bind.JAXBException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.goobi.production.cli.helper.WikiFieldHelper;
import org.kitodo.api.docket.DocketData;
import org.kitodo.api.docket.DocketInterface;
import org.kitodo.api.filemanagement.ProcessSubType;
import org.kitodo.api.filemanagement.filters.FileNameBeginsAndEndsWithFilter;
import org.kitodo.api.filemanagement.filters.FileNameEndsAndDoesNotBeginWithFilter;
import org.kitodo.api.ugh.ContentFileInterface;
import org.kitodo.api.ugh.DigitalDocumentInterface;
import org.kitodo.api.ugh.DocStructInterface;
import org.kitodo.api.ugh.FileformatInterface;
import org.kitodo.api.ugh.MetadataInterface;
import org.kitodo.api.ugh.MetsModsImportExportInterface;
import org.kitodo.api.ugh.PrefsInterface;
import org.kitodo.api.ugh.VirtualFileGroupInterface;
import org.kitodo.api.ugh.exceptions.PreferencesException;
import org.kitodo.api.ugh.exceptions.ReadException;
import org.kitodo.api.ugh.exceptions.WriteException;
import org.kitodo.config.Parameters;
import org.kitodo.config.xml.fileformats.FileFormatsConfig;
import org.kitodo.data.database.beans.Batch;
import org.kitodo.data.database.beans.Batch.Type;
import org.kitodo.data.database.beans.Docket;
import org.kitodo.data.database.beans.Folder;
import org.kitodo.data.database.beans.LinkingMode;
import org.kitodo.data.database.beans.Process;
import org.kitodo.data.database.beans.Project;
import org.kitodo.data.database.beans.Property;
import org.kitodo.data.database.beans.Ruleset;
import org.kitodo.data.database.beans.Task;
import org.kitodo.data.database.beans.User;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.data.database.helper.enums.IndexAction;
import org.kitodo.data.database.helper.enums.MetadataFormat;
import org.kitodo.data.database.helper.enums.TaskStatus;
import org.kitodo.data.database.persistence.ProcessDAO;
import org.kitodo.data.elasticsearch.exceptions.CustomResponseException;
import org.kitodo.data.elasticsearch.index.Indexer;
import org.kitodo.data.elasticsearch.index.type.ProcessType;
import org.kitodo.data.elasticsearch.index.type.enums.BatchTypeField;
import org.kitodo.data.elasticsearch.index.type.enums.ProcessTypeField;
import org.kitodo.data.elasticsearch.search.Searcher;
import org.kitodo.data.exceptions.DataException;
import org.kitodo.dto.BatchDTO;
import org.kitodo.dto.ProcessDTO;
import org.kitodo.dto.ProjectDTO;
import org.kitodo.dto.PropertyDTO;
import org.kitodo.dto.TaskDTO;
import org.kitodo.dto.UserDTO;
import org.kitodo.enums.ObjectType;
import org.kitodo.legacy.UghImplementation;
import org.kitodo.serviceloader.KitodoServiceLoader;
import org.kitodo.services.ServiceManager;
import org.kitodo.services.data.base.TitleSearchService;
import org.kitodo.services.file.FileService;

public class ProcessService extends TitleSearchService<Process, ProcessDTO, ProcessDAO> {

    private final MetadataLock msp = new MetadataLock();
    private final ServiceManager serviceManager = new ServiceManager();
    private final FileService fileService = serviceManager.getFileService();
    private static final Logger logger = LogManager.getLogger(ProcessService.class);
    private static ProcessService instance = null;
    private boolean showClosedProcesses = false;
    private boolean showInactiveProjects = false;
    private static final String DIRECTORY_PREFIX = ConfigCore.getParameter("DIRECTORY_PREFIX", "orig");
    private static final String DIRECTORY_SUFFIX = ConfigCore.getParameter("DIRECTORY_SUFFIX", "tif");
    private static final String SUFFIX = ConfigCore.getParameter("MetsEditorDefaultSuffix", "");
    private static final String EXPORT_DIR_DELETE = "errorDirectoryDeleting";
    private static final String ERROR_EXPORT = "errorExport";
    private static final String CLOSED = "closed";
    private static final String IN_PROCESSING = "inProcessing";
    private static final String LOCKED = "locked";
    private static final String OPEN = "open";
    private static final String PROCESS_TITLE = "(processtitle)";
    private static final boolean CREATE_ORIG_FOLDER_IF_NOT_EXISTS = ConfigCore
            .getBooleanParameter(Parameters.CREATE_ORIG_FOLDER_IF_NOT_EXISTS);
    private static final boolean USE_ORIG_FOLDER = ConfigCore.getBooleanParameter(Parameters.USE_ORIG_FOLDER, true);

    /**
     * Constructor with Searcher and Indexer assigning.
     */
    private ProcessService() {
        super(new ProcessDAO(), new ProcessType(), new Indexer<>(Process.class), new Searcher(Process.class));
    }

    /**
     * Return singleton variable of type ProcessService.
     *
     * @return unique instance of ProcessService
     */
    public static ProcessService getInstance() {
        if (Objects.equals(instance, null)) {
            synchronized (ProcessService.class) {
                if (Objects.equals(instance, null)) {
                    instance = new ProcessService();
                }
            }
        }
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ProcessDTO> findAll(String sort, Integer offset, Integer size, Map filters) throws DataException {
        Map<String, String> filterMap = (Map<String, String>) filters;

        BoolQueryBuilder query;

        if (Objects.equals(filters, null) || filters.isEmpty()) {
            return convertJSONObjectsToDTOs(findBySort(false, true, sort, offset, size), false);
        }

        query = readFilters(filterMap);

        String queryString = "";
        if (!Objects.equals(query, null)) {
            queryString = query.toString();
        }
        return convertJSONObjectsToDTOs(searcher.findDocuments(queryString, sort, offset, size), false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String createCountQuery(Map filters) throws DataException {
        Map<String, String> filterMap = (Map<String, String>) filters;

        BoolQueryBuilder query;

        if (Objects.equals(filters, null) || filters.isEmpty()) {
            query = new BoolQueryBuilder();
            query.must(getQuerySortHelperStatus(false));
            query.must(getQueryProjectActive(true));
        } else {
            query = readFilters(filterMap);
        }

        if (Objects.nonNull(query)) {
            return query.toString();
        }
        return "";
    }

    private BoolQueryBuilder readFilters(Map<String, String> filterMap) throws DataException {
        BoolQueryBuilder query = null;

        for (Map.Entry<String, String> entry : filterMap.entrySet()) {
            query = serviceManager.getFilterService().queryBuilder(entry.getValue(), ObjectType.PROCESS, false, false);
            if (!this.showClosedProcesses) {
                query.must(getQuerySortHelperStatus(false));
            }
            if (!this.showInactiveProjects) {
                query.must(getQueryProjectActive(true));
            }
        }
        return query;
    }

    /**
     * Method saves or removes batches, tasks and project related to modified
     * process.
     *
     * @param process
     *            object
     */
    @Override
    protected void manageDependenciesForIndex(Process process)
            throws CustomResponseException, DAOException, DataException, IOException {
        manageBatchesDependenciesForIndex(process);
        manageProjectDependenciesForIndex(process);
        manageTaskDependenciesForIndex(process);
        manageTemplatesDependenciesForIndex(process);
        manageWorkpiecesDependenciesForIndex(process);
        managePropertiesDependenciesForIndex(process);
    }

    /**
     * Check if IndexAction flag is delete. If true remove process from list of
     * processes and re-save batch, if false only re-save batch object.
     *
     * @param process
     *            object
     */
    private void manageBatchesDependenciesForIndex(Process process) throws CustomResponseException, IOException {
        if (process.getIndexAction() == IndexAction.DELETE) {
            for (Batch batch : process.getBatches()) {
                batch.getProcesses().remove(process);
                serviceManager.getBatchService().saveToIndex(batch, false);
            }
        } else {
            for (Batch batch : process.getBatches()) {
                serviceManager.getBatchService().saveToIndex(batch, false);
            }
        }
    }

    /**
     * Add process to project, if project is assigned to process.
     *
     * @param process
     *            object
     */
    private void manageProjectDependenciesForIndex(Process process) throws CustomResponseException, IOException {
        if (process.getProject() != null) {
            serviceManager.getProjectService().saveToIndex(process.getProject(), false);
        }
    }

    /**
     * Remove properties if process is removed, add properties if process is
     * marked as indexed.
     *
     * @param process
     *            object
     */
    private void managePropertiesDependenciesForIndex(Process process) throws CustomResponseException, IOException {
        if (process.getIndexAction() == IndexAction.DELETE) {
            for (Property property : process.getProperties()) {
                serviceManager.getPropertyService().removeFromIndex(property, false);
            }
        } else {
            for (Property property : process.getProperties()) {
                serviceManager.getPropertyService().saveToIndex(property, false);
            }
        }
    }

    /**
     * Check IndexAction flag in for process object. If DELETE remove all tasks
     * from index, if other call saveOrRemoveTaskInIndex() method.
     *
     * @param process
     *            object
     */
    private void manageTaskDependenciesForIndex(Process process)
            throws CustomResponseException, DAOException, IOException, DataException {
        if (process.getIndexAction() == IndexAction.DELETE) {
            for (Task task : process.getTasks()) {
                serviceManager.getTaskService().removeFromIndex(task, false);
            }
        } else {
            saveOrRemoveTasksInIndex(process);
        }
    }

    /**
     * Compare index and database, according to comparisons results save or
     * remove tasks.
     *
     * @param process
     *            object
     */
    private void saveOrRemoveTasksInIndex(Process process)
            throws CustomResponseException, DAOException, IOException, DataException {
        List<Integer> database = new ArrayList<>();
        List<Integer> index = new ArrayList<>();

        for (Task task : process.getTasks()) {
            database.add(task.getId());
            serviceManager.getTaskService().saveToIndex(task, false);
        }

        List<JsonObject> searchResults = serviceManager.getTaskService().findByProcessId(process.getId());
        for (JsonObject object : searchResults) {
            index.add(getIdFromJSONObject(object));
        }

        List<Integer> missingInIndex = findMissingValues(database, index);
        List<Integer> notNeededInIndex = findMissingValues(index, database);
        for (Integer missing : missingInIndex) {
            serviceManager.getTaskService().saveToIndex(serviceManager.getTaskService().getById(missing), false);
        }

        for (Integer notNeeded : notNeededInIndex) {
            serviceManager.getTaskService().removeFromIndex(notNeeded, false);
        }
    }

    /**
     * Remove template if process is removed, add template if process is marked
     * as template.
     *
     * @param process
     *            object
     */
    private void manageTemplatesDependenciesForIndex(Process process) throws CustomResponseException, IOException {
        if (process.getIndexAction() == IndexAction.DELETE) {
            for (Property template : process.getTemplates()) {
                serviceManager.getPropertyService().removeFromIndex(template, false);
            }
        } else {
            for (Property template : process.getTemplates()) {
                serviceManager.getPropertyService().saveToIndex(template, false);
            }
        }
    }

    /**
     * Remove workpiece if process is removed, add workpiece if process is
     * marked as workpiece.
     *
     * @param process
     *            object
     */
    private void manageWorkpiecesDependenciesForIndex(Process process) throws CustomResponseException, IOException {
        if (process.getIndexAction() == IndexAction.DELETE) {
            for (Property workpiece : process.getWorkpieces()) {
                serviceManager.getPropertyService().removeFromIndex(workpiece, false);
            }
        } else {
            for (Property workpiece : process.getWorkpieces()) {
                serviceManager.getPropertyService().saveToIndex(workpiece, false);
            }
        }
    }

    /**
     * Compare two list and return difference between them.
     *
     * @param firstList
     *            list from which records can be remove
     * @param secondList
     *            records stored here will be removed from firstList
     * @return difference between two lists
     */
    private List<Integer> findMissingValues(List<Integer> firstList, List<Integer> secondList) {
        List<Integer> newList = new ArrayList<>(firstList);
        newList.removeAll(secondList);
        return newList;
    }

    /**
     * Save list of processes to database.
     *
     * @param list
     *            of processes
     */
    public void saveList(List<Process> list) throws DAOException {
        dao.saveList(list);
    }

    @Override
    public Long countDatabaseRows() throws DAOException {
        return countDatabaseRows("SELECT COUNT(*) FROM Process");
    }

    @Override
    public void refresh(Process process) {
        dao.refresh(process);
    }

    /**
     * Find processes by id of project.
     *
     * @param id
     *            of project
     * @return list of ProcessDTO objects with processes for specific process id
     */
    public List<ProcessDTO> findByProjectId(Integer id, boolean related) throws DataException {
        List<JsonObject> processes = searcher.findDocuments(getQueryProjectId(id).toString());
        return convertJSONObjectsToDTOs(processes, related);
    }

    private QueryBuilder getQueryProjectId(Integer id) {
        return createSimpleQuery(ProcessTypeField.PROJECT_ID.getKey(), id, true);
    }

    /**
     * Get query for find process by project title.
     *
     * @param title
     *            as String
     * @return QueryBuilder object
     */
    public QueryBuilder getQueryProjectTitle(String title) {
        return createSimpleQuery(ProcessTypeField.PROJECT_TITLE.getKey(), title, true, Operator.AND);
    }

    /**
     * Find processes by docket.
     *
     * @param docket
     *            of project
     * @return list of JSON objects with processes for specific docket
     */
    public List<JsonObject> findByDocket(Docket docket) throws DataException {
        QueryBuilder query = createSimpleQuery(ProcessTypeField.DOCKET.getKey(), docket.getId(), true);
        return searcher.findDocuments(query.toString());
    }

    /**
     * Find processes by ruleset.
     *
     * @param ruleset
     *            of project
     * @return list of JSON objects with processes for specific ruleset
     */
    public List<JsonObject> findByRuleset(Ruleset ruleset) throws DataException {
        QueryBuilder query = createSimpleQuery(ProcessTypeField.RULESET.getKey(), ruleset.getId(), true);
        return searcher.findDocuments(query.toString());
    }

    /**
     * Find processes by title of project.
     *
     * @param title
     *            of process
     * @return list of JSON objects with processes for specific process id
     */
    List<JsonObject> findByProjectTitle(String title) throws DataException {
        return searcher.findDocuments(getQueryProjectTitle(title).toString());
    }

    /**
     * Find processes by id of batch.
     *
     * @param id
     *            of process
     * @return list of JSON objects with processes for specific batch id
     */
    List<JsonObject> findByBatchId(Integer id) throws DataException {
        QueryBuilder query = createSimpleQuery("batches.id", id, true);
        return searcher.findDocuments(query.toString());
    }

    /**
     * Find processes by title of batch.
     *
     * @param title
     *            of batch
     * @return list of JSON objects with processes for specific batch title
     */
    List<JsonObject> findByBatchTitle(String title) throws DataException {
        QueryBuilder query = createSimpleQuery("batches.title", title, true, Operator.AND);
        return searcher.findDocuments(query.toString());
    }

    /**
     * Find process by property.
     *
     * @param title
     *            of property as String
     * @param value
     *            of property as String
     * @param contains
     *            true or false
     * @return list of process JSONObjects
     */
    public List<JsonObject> findByProcessProperty(String title, String value, boolean contains) throws DataException {
        return findByProperty(title, value, "process", "properties.id", contains);
    }

    /**
     * Find process by template.
     *
     * @param title
     *            of property as String
     * @param value
     *            of property as String
     * @param contains
     *            true or false
     * @return list of process JSONObjects
     */
    public List<JsonObject> findByTemplateProperty(String title, String value, boolean contains) throws DataException {
        return findByProperty(title, value, "template", "templates.id", contains);
    }

    /**
     * Find process by workpiece.
     *
     * @param title
     *            of property as String
     * @param value
     *            of property as String
     * @param contains
     *            true or false
     * @return list of process JSONObjects
     */
    public List<JsonObject> findByWorkpieceProperty(String title, String value, boolean contains) throws DataException {
        return findByProperty(title, value, "workpiece", "workpieces.id", contains);
    }

    /**
     * Find processes by property.
     *
     * @param title
     *            of property
     * @param value
     *            of property
     * @param contains
     *            true or false
     * @return list of JSON objects with processes for specific property
     */
    private List<JsonObject> findByProperty(String title, String value, String type, String key, boolean contains)
            throws DataException {
        Set<Integer> propertyIds = new HashSet<>();

        List<JsonObject> properties;
        if (value == null) {
            properties = serviceManager.getPropertyService().findByTitle(title, type, contains);
        } else if (title == null) {
            properties = serviceManager.getPropertyService().findByValue(value, type, contains);
        } else {
            properties = serviceManager.getPropertyService().findByTitleAndValue(title, value, type, contains);
        }

        for (JsonObject property : properties) {
            propertyIds.add(getIdFromJSONObject(property));
        }
        return searcher.findDocuments(createSetQuery(key, propertyIds, true).toString());
    }

    private List<JsonObject> findBySort(boolean closed, boolean active, String sort, Integer offset, Integer size)
            throws DataException {
        BoolQueryBuilder query = new BoolQueryBuilder();
        query.must(getQuerySortHelperStatus(closed));
        query.must(getQueryProjectActive(active));
        return searcher.findDocuments(query.toString(), sort, offset, size);
    }

    private List<JsonObject> findBySortHelperStatusProjectActive(boolean closed, boolean active, String sort)
            throws DataException {
        BoolQueryBuilder query = new BoolQueryBuilder();
        query.must(getQuerySortHelperStatus(closed));
        query.must(getQueryProjectActive(active));
        return searcher.findDocuments(query.toString(), sort);
    }

    private List<JsonObject> findBySortHelperStatus(boolean closed, String sort) throws DataException {
        BoolQueryBuilder query = new BoolQueryBuilder();
        query.must(getQuerySortHelperStatus(closed));
        return searcher.findDocuments(query.toString(), sort);
    }

    private List<JsonObject> findByActive(boolean active, String sort) throws DataException {
        return searcher.findDocuments(getQueryProjectActive(active).toString(), sort);
    }

    List<ProcessDTO> findByProjectIds(Set<Integer> projectIds, boolean related) throws DataException {
        QueryBuilder query = createSetQuery("project.id", projectIds, true);
        return convertJSONObjectsToDTOs(searcher.findDocuments(query.toString()), related);
    }

    /**
     * Get query for sort helper status.
     *
     * @param closed
     *            true or false
     * @return query as QueryBuilder
     */
    public QueryBuilder getQuerySortHelperStatus(boolean closed) {
        BoolQueryBuilder query = new BoolQueryBuilder();
        query.should(createSimpleQuery(ProcessTypeField.SORT_HELPER_STATUS.getKey(), "100000000", closed));
        query.should(createSimpleQuery(ProcessTypeField.SORT_HELPER_STATUS.getKey(), "100000000000", closed));
        return query;
    }

    /**
     * Get query for active projects.
     *
     * @param active
     *            true or false
     * @return query as QueryBuilder
     */
    public QueryBuilder getQueryProjectActive(boolean active) {
        return createSimpleQuery(ProcessTypeField.PROJECT_ACTIVE.getKey(), active, true);
    }

    /**
     * Sort results by creation date.
     *
     * @param sortOrder
     *            ASC or DESC as SortOrder
     * @return sort
     */
    public String sortByCreationDate(SortOrder sortOrder) {
        return SortBuilders.fieldSort(ProcessTypeField.CREATION_DATE.getKey()).order(sortOrder).toString();
    }

    /**
     * Convert DTO to bean object.
     *
     * @param processDTO
     *            DTO object
     * @return bean object
     */
    public Process convertDtoToBean(ProcessDTO processDTO) throws DAOException {
        return serviceManager.getProcessService().getById(processDTO.getId());
    }

    /**
     * Convert list of DTOs to list of beans.
     *
     * @param dtos
     *            list of DTO objects
     * @return list of beans
     */
    public List<Process> convertDtosToBeans(List<ProcessDTO> dtos) throws DAOException {
        List<Process> processes = new ArrayList<>();
        for (ProcessDTO processDTO : dtos) {
            processes.add(convertDtoToBean(processDTO));
        }
        return processes;
    }

    @Override
    public ProcessDTO convertJSONObjectToDTO(JsonObject jsonObject, boolean related) throws DataException {
        ProcessDTO processDTO = new ProcessDTO();
        processDTO.setId(getIdFromJSONObject(jsonObject));
        JsonObject processJSONObject = jsonObject.getJsonObject("_source");
        processDTO.setTitle(ProcessTypeField.TITLE.getStringValue(processJSONObject));
        processDTO.setOutputName(ProcessTypeField.OUTPUT_NAME.getStringValue(processJSONObject));
        processDTO.setWikiField(ProcessTypeField.WIKI_FIELD.getStringValue(processJSONObject));
        processDTO.setCreationDate(ProcessTypeField.CREATION_DATE.getStringValue(processJSONObject));
        processDTO.setProperties(convertRelatedJSONObjectToDTO(processJSONObject, ProcessTypeField.PROPERTIES.getKey(),
            serviceManager.getPropertyService()));
        processDTO.setSortedCorrectionSolutionMessages(getSortedCorrectionSolutionMessages(processDTO));
        processDTO.setSortHelperArticles(ProcessTypeField.SORT_HELPER_ARTICLES.getIntValue(processJSONObject));
        processDTO.setSortHelperDocstructs(processJSONObject.getInt(ProcessTypeField.SORT_HELPER_DOCSTRUCTS.getKey()));
        processDTO.setSortHelperImages(ProcessTypeField.SORT_HELPER_IMAGES.getIntValue(processJSONObject));
        processDTO.setSortHelperMetadata(ProcessTypeField.SORT_HELPER_METADATA.getIntValue(processJSONObject));
        processDTO.setTifDirectoryExists(
            checkIfTifDirectoryExists(processDTO.getId(), processDTO.getTitle(), processDTO.getProcessBaseUri()));
        processDTO.setBatches(getBatchesForProcessDTO(processJSONObject));
        if (!related) {
            convertRelatedJSONObjects(processJSONObject, processDTO);
        } else {
            ProjectDTO projectDTO = new ProjectDTO();
            projectDTO.setId(ProcessTypeField.PROJECT_ID.getIntValue(processJSONObject));
            projectDTO.setTitle(ProcessTypeField.PROJECT_TITLE.getStringValue(processJSONObject));
            projectDTO.setActive(ProcessTypeField.PROJECT_ACTIVE.getBooleanValue(processJSONObject));
            processDTO.setProject(projectDTO);
        }
        return processDTO;
    }

    private void convertRelatedJSONObjects(JsonObject jsonObject, ProcessDTO processDTO) throws DataException {
        Integer project = ProcessTypeField.PROJECT_ID.getIntValue(jsonObject);
        if (project > 0) {
            processDTO.setProject(serviceManager.getProjectService().findById(project));
        }

        processDTO.setBatchID(getBatchID(processDTO));
        // TODO: leave it for now - right now it displays only status
        processDTO.setTasks(convertRelatedJSONObjectToDTO(jsonObject, ProcessTypeField.TASKS.getKey(),
            serviceManager.getTaskService()));
        processDTO.setImageFolderInUse(isImageFolderInUse(processDTO));
        processDTO.setProgressClosed(getProgressClosed(null, processDTO.getTasks()));
        processDTO.setProgressInProcessing(getProgressInProcessing(null, processDTO.getTasks()));
        processDTO.setProgressOpen(getProgressOpen(null, processDTO.getTasks()));
        processDTO.setProgressLocked(getProgressLocked(null, processDTO.getTasks()));
        processDTO.setBlockedUser(getBlockedUser(processDTO));
    }

    private List<BatchDTO> getBatchesForProcessDTO(JsonObject jsonObject) throws DataException {
        JsonArray jsonArray = ProcessTypeField.BATCHES.getJsonArray(jsonObject);
        List<BatchDTO> batchDTOList = new ArrayList<>();
        for (JsonValue singleObject : jsonArray) {
            BatchDTO batchDTO = new BatchDTO();
            batchDTO.setId(BatchTypeField.ID.getIntValue(singleObject.asJsonObject()));
            batchDTO.setTitle(BatchTypeField.TITLE.getStringValue(singleObject.asJsonObject()));
            batchDTO.setType(BatchTypeField.TYPE.getStringValue(singleObject.asJsonObject()));
            batchDTOList.add(batchDTO);
        }
        return batchDTOList;
    }

    /**
     * Check if process is assigned only to one LOGISTIC batch.
     *
     * @param batchDTOList
     *            list of batches for checkout
     * @return true or false
     */
    boolean isProcessAssignedToOnlyOneLogisticBatch(List<BatchDTO> batchDTOList) {
        List<BatchDTO> result = new ArrayList<>(batchDTOList);
        result.removeIf(batch -> !(batch.getType().equals("LOGISTIC")));
        return result.size() == 1;
    }

    /**
     * Get title without white spaces.
     *
     * @param title
     *            of process
     * @return title with '__' instead of ' '
     */
    public String getNormalizedTitle(String title) {
        return title.replace(" ", "__");
    }

    /**
     * Returns the batches of the desired type for a process.
     *
     * @param type
     *            of batches to return
     * @return all batches of the desired type
     */
    public List<Batch> getBatchesByType(Process process, Type type) {
        List<Batch> batches = process.getBatches();
        if (type != null) {
            List<Batch> result = new ArrayList<>(batches);
            result.removeIf(batch -> !(batch.getType().equals(type)));
            return result;
        }
        return batches;
    }

    /**
     * Get blocked user for ProcessDTO.
     *
     * @return blocked metadata (user)
     */
    UserDTO getBlockedUser(ProcessDTO process) {
        UserDTO result = null;
        if (MetadataLock.isLocked(process.getId())) {
            String userID = this.msp.getLockBenutzer(process.getId());
            try {
                result = serviceManager.getUserService().findById(Integer.valueOf(userID));
            } catch (DataException | RuntimeException e) {
                Helper.setErrorMessage("userNotFound", logger, e);
            }
        }
        return result;
    }

    /**
     * Get blocked user for Process.
     *
     * @return blocked metadata (user)
     */
    public User getBlockedUser(Process process) {
        User result = null;
        if (MetadataLock.isLocked(process.getId())) {
            String userID = this.msp.getLockBenutzer(process.getId());
            try {
                result = serviceManager.getUserService().getById(Integer.valueOf(userID));
            } catch (DAOException | RuntimeException e) {
                Helper.setErrorMessage("userNotFound", logger, e);
            }
        }
        return result;
    }

    public long getBlockedMinutes(Process process) {
        return this.msp.getLockSekunden(process.getId()) / 60;
    }

    public long getBlockedSeconds(Process process) {
        return this.msp.getLockSekunden(process.getId()) % 60;
    }

    /**
     * Get directory for tig images.
     *
     * @param useFallBack
     *            add description
     * @param process
     *            object
     * @return tif directory
     */
    public URI getImagesTifDirectory(boolean useFallBack, Process process) throws IOException {
        URI dir = fileService.getProcessSubTypeURI(process, ProcessSubType.IMAGE, null);

        /* nur die _tif-Ordner anzeigen, die nicht mir orig_ anfangen */
        FilenameFilter filterDirectory = new FileNameEndsAndDoesNotBeginWithFilter(DIRECTORY_PREFIX + "_",
                "_" + DIRECTORY_SUFFIX);
        URI tifDirectory = null;
        List<URI> directories = fileService.getSubUris(filterDirectory, dir);
        for (URI directory : directories) {
            tifDirectory = directory;
        }

        if (tifDirectory == null && useFallBack && !SUFFIX.equals("")) {
            List<URI> folderList = fileService.getSubUrisForProcess(null, process, ProcessSubType.IMAGE, "");
            for (URI folder : folderList) {
                if (folder.toString().endsWith(SUFFIX)) {
                    tifDirectory = folder;
                    break;
                }
            }
        }

        tifDirectory = getImageDirectory(useFallBack, dir, tifDirectory);

        URI result = fileService.getProcessSubTypeURI(process, ProcessSubType.IMAGE, null);

        if (tifDirectory == null) {
            tifDirectory = URI
                    .create(result.getRawPath() + getNormalizedTitle(process.getTitle()) + "_" + DIRECTORY_SUFFIX);
        }

        if (!USE_ORIG_FOLDER && CREATE_ORIG_FOLDER_IF_NOT_EXISTS) {
            fileService.createDirectory(result, tifDirectory.getRawPath());
        }

        return tifDirectory;
    }

    /**
     * Get directory for tig images.
     *
     * @param useFallBack
     *            add description
     * @param processId
     *            id of process object
     * @param processTitle
     *            title of process object
     * @param processBaseURI
     *            base URI of process object
     * @return tif directory
     */
    public URI getImagesTifDirectory(boolean useFallBack, Integer processId, String processTitle, URI processBaseURI)
            throws DAOException, IOException {
        URI dir = fileService.getProcessSubTypeURI(processId, processTitle, processBaseURI, ProcessSubType.IMAGE, null);

        /* nur die _tif-Ordner anzeigen, die nicht mir orig_ anfangen */
        FilenameFilter filterDirectory = new FileNameEndsAndDoesNotBeginWithFilter(DIRECTORY_PREFIX + "_",
                "_" + DIRECTORY_SUFFIX);
        URI tifDirectory = null;
        List<URI> directories = fileService.getSubUris(filterDirectory, dir);
        for (URI directory : directories) {
            tifDirectory = directory;
        }

        if (tifDirectory == null && useFallBack && !SUFFIX.equals("")) {
            List<URI> folderList = fileService.getSubUrisForProcess(null, processId, processTitle, processBaseURI,
                ProcessSubType.IMAGE, "");
            for (URI folder : folderList) {
                if (folder.toString().endsWith(SUFFIX)) {
                    tifDirectory = folder;
                    break;
                }
            }
        }

        tifDirectory = getImageDirectory(useFallBack, dir, tifDirectory);

        URI result = fileService.getProcessSubTypeURI(processId, processTitle, processBaseURI, ProcessSubType.IMAGE,
            null);

        if (tifDirectory == null) {
            tifDirectory = URI.create(result.getRawPath() + getNormalizedTitle(processTitle) + "_" + DIRECTORY_SUFFIX);
        }

        if (!USE_ORIG_FOLDER && CREATE_ORIG_FOLDER_IF_NOT_EXISTS) {
            fileService.createDirectory(result, tifDirectory.getRawPath());
        }

        return tifDirectory;
    }

    /**
     * Check if Tif directory exists.
     *
     * @return true if the Tif-Image-Directory exists, false if not
     */
    Boolean checkIfTifDirectoryExists(Integer processId, String processTitle, String processBaseURI) {
        URI testMe;
        try {
            if (processBaseURI != null) {
                testMe = getImagesTifDirectory(true, processId, processTitle, URI.create(processBaseURI));
            } else {
                testMe = getImagesTifDirectory(true, processId, processTitle, null);
            }
            return fileService.getSubUris(testMe) != null && fileService.fileExist(testMe)
                    && !fileService.getSubUris(testMe).isEmpty();
        } catch (DAOException | IOException e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get images origin directory.
     *
     * @param useFallBack
     *            as boolean
     * @param process
     *            object
     * @return path
     */
    public URI getImagesOrigDirectory(boolean useFallBack, Process process) throws IOException {
        if (USE_ORIG_FOLDER) {
            URI dir = fileService.getProcessSubTypeURI(process, ProcessSubType.IMAGE, null);

            /* nur die _tif-Ordner anzeigen, die mit orig_ anfangen */
            FilenameFilter filterDirectory = new FileNameBeginsAndEndsWithFilter(DIRECTORY_PREFIX + "_",
                    "_" + DIRECTORY_SUFFIX);
            URI origDirectory = null;
            List<URI> directories = fileService.getSubUris(filterDirectory, dir);
            for (URI directory : directories) {
                origDirectory = directory;
            }

            if (origDirectory == null && useFallBack && !SUFFIX.equals("")) {
                List<URI> folderList = fileService.getSubUris(dir);
                for (URI folder : folderList) {
                    if (folder.toString().endsWith(SUFFIX)) {
                        origDirectory = folder;
                        break;
                    }
                }
            }

            origDirectory = getImageDirectory(useFallBack, dir, origDirectory);

            URI result = fileService.getProcessSubTypeURI(process, ProcessSubType.IMAGE, null);

            if (origDirectory == null) {
                origDirectory = URI.create(result.toString() + DIRECTORY_PREFIX + "_"
                        + getNormalizedTitle(process.getTitle()) + "_" + DIRECTORY_SUFFIX);
            }

            if (CREATE_ORIG_FOLDER_IF_NOT_EXISTS && process.getSortHelperStatus() != null
                    && process.getSortHelperStatus().equals("100000000")) {
                fileService.createDirectory(result, origDirectory.getRawPath());
            }
            return origDirectory;
        } else {
            return getImagesTifDirectory(useFallBack, process);
        }
    }

    private URI getImageDirectory(boolean useFallBack, URI directory, URI imageDirectory) {
        if (Objects.nonNull(imageDirectory) && useFallBack && !SUFFIX.equals("")) {
            URI tif = imageDirectory;
            List<URI> files = fileService.getSubUris(tif);
            if (files.isEmpty()) {
                List<URI> folderList = fileService.getSubUris(directory);
                for (URI folder : folderList) {
                    if (folder.toString().endsWith(SUFFIX) && !folder.getPath().startsWith(DIRECTORY_PREFIX)) {
                        imageDirectory = folder;
                        break;
                    }
                }
            }
        }
        return imageDirectory;
    }

    /**
     * Get process data directory.
     *
     * @param process
     *            object
     * @return path
     */
    public URI getProcessDataDirectory(Process process) {
        if (process.getProcessBaseUri() == null) {
            process.setProcessBaseUri(fileService.getProcessBaseUriForExistingProcess(process));
            try {
                save(process);
            } catch (DataException e) {
                logger.error(e.getMessage(), e);
                return URI.create("");
            }
        }
        return process.getProcessBaseUri();
    }

    /**
     * The function getBatchID returns the batches the process is associated
     * with as readable text as read-only property "batchID".
     *
     * @return the batches the process is in
     */
    public String getBatchID(ProcessDTO process) {
        if (process.getBatches().isEmpty()) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        BatchService batchService = serviceManager.getBatchService();
        for (BatchDTO batch : process.getBatches()) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(batchService.getLabel(batch));
        }
        return result.toString();
    }

    /**
     * Get size of tasks' list.
     *
     * @param process
     *            object
     * @return size
     */
    public int getTasksSize(Process process) {
        if (process.getTasks() == null) {
            return 0;
        } else {
            return process.getTasks().size();
        }
    }

    /**
     * Get size of properties' list.
     *
     * @param process
     *            object
     * @return size
     */
    public int getPropertiesSize(Process process) {
        if (process.getProperties() == null) {
            return 0;
        } else {
            return process.getProperties().size();
        }
    }

    /**
     * Get size of workpieces' list.
     *
     * @param process
     *            object
     * @return size
     */
    public int getWorkpiecesSize(Process process) {
        if (process.getWorkpieces() == null) {
            return 0;
        } else {
            return process.getWorkpieces().size();
        }
    }

    /**
     * Get size of templates' list.
     *
     * @param process
     *            object
     * @return size
     */
    public int getTemplatesSize(Process process) {
        if (process.getTemplates() == null) {
            return 0;
        } else {
            return process.getTemplates().size();
        }
    }

    /**
     * Get current task.
     *
     * @param process
     *            object
     * @return current task
     */
    public Task getCurrentTask(Process process) {
        for (Task task : process.getTasks()) {
            if (task.getProcessingStatusEnum() == TaskStatus.OPEN
                    || task.getProcessingStatusEnum() == TaskStatus.INWORK) {
                return task;
            }
        }
        return null;
    }

    public String getCreationDateAsString(Process process) {
        return Helper.getDateAsFormattedString(process.getCreationDate());
    }

    /**
     * Get full progress for process.
     *
     * @param tasksBean
     *            list of Task bean objects
     * @param tasksDTO
     *            list of TaskDTO objects
     * @return string
     */
    public String getProgress(List<Task> tasksBean, List<TaskDTO> tasksDTO) {
        HashMap<String, Integer> tasks = getCalculationForProgress(tasksBean, tasksDTO);

        double closed = (tasks.get(CLOSED) * 100)
                / (double) (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));
        double inProcessing = (tasks.get(IN_PROCESSING) * 100)
                / (double) (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));
        double open = (tasks.get(OPEN) * 100)
                / (double) (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));
        double locked = (tasks.get(LOCKED) * 100)
                / (double) (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));

        DecimalFormat decimalFormat = new DecimalFormat("#000");
        return decimalFormat.format(closed) + decimalFormat.format(inProcessing) + decimalFormat.format(open)
                + decimalFormat.format(locked);
    }

    /**
     * Get progress for closed tasks.
     *
     * @param tasksBean
     *            list of Task bean objects
     * @param tasksDTO
     *            list of TaskDTO objects
     * @return progress for closed steps
     */
    public int getProgressClosed(List<Task> tasksBean, List<TaskDTO> tasksDTO) {
        HashMap<String, Integer> tasks = getCalculationForProgress(tasksBean, tasksDTO);

        return (tasks.get(CLOSED) * 100)
                / (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));
    }

    /**
     * Get progress for processed tasks.
     *
     * @param tasksBean
     *            list of Task bean objects
     * @param tasksDTO
     *            list of TaskDTO objects
     * @return progress for processed tasks
     */
    public int getProgressInProcessing(List<Task> tasksBean, List<TaskDTO> tasksDTO) {
        HashMap<String, Integer> tasks = getCalculationForProgress(tasksBean, tasksDTO);

        return (tasks.get(IN_PROCESSING) * 100)
                / (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));
    }

    /**
     * Get progress for open tasks.
     *
     * @param tasksBean
     *            list of Task bean objects
     * @param tasksDTO
     *            list of TaskDTO objects
     * @return return progress for open tasks
     */
    public int getProgressOpen(List<Task> tasksBean, List<TaskDTO> tasksDTO) {
        HashMap<String, Integer> tasks = getCalculationForProgress(tasksBean, tasksDTO);
        return (tasks.get(OPEN) * 100)
                / (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));
    }

    /**
     * Get progress for open tasks.
     *
     * @param tasksBean
     *            list of Task bean objects
     * @param tasksDTO
     *            list of TaskDTO objects
     * @return return progress for open tasks
     */
    public int getProgressLocked(List<Task> tasksBean, List<TaskDTO> tasksDTO) {
        HashMap<String, Integer> tasks = getCalculationForProgress(tasksBean, tasksDTO);
        return (tasks.get(LOCKED) * 100)
                / (tasks.get(CLOSED) + tasks.get(IN_PROCESSING) + tasks.get(OPEN) + tasks.get(LOCKED));
    }

    private HashMap<String, Integer> getCalculationForProgress(List<Task> tasksBean, List<TaskDTO> tasksDTO) {
        if (Objects.nonNull(tasksBean)) {
            return calculationForProgress(tasksBean);
        } else {
            return calculationForProgress(tasksDTO);
        }
    }

    private HashMap<String, Integer> calculationForProgress(List<?> tasks) {
        HashMap<String, Integer> results = new HashMap<>();
        int open = 0;
        int inProcessing = 0;
        int closed = 0;
        int locked = 0;

        for (Object task : tasks) {
            if (task instanceof Task) {
                if (((Task) task).getProcessingStatusEnum() == TaskStatus.DONE) {
                    closed++;
                } else if (((Task) task).getProcessingStatusEnum() == TaskStatus.OPEN) {
                    open++;
                } else if (((Task) task).getProcessingStatusEnum() == TaskStatus.LOCKED) {
                    locked++;
                } else {
                    inProcessing++;
                }
            } else if (task instanceof TaskDTO) {
                if (((TaskDTO) task).getProcessingStatus() == TaskStatus.DONE) {
                    closed++;
                } else if (((TaskDTO) task).getProcessingStatus() == TaskStatus.OPEN) {
                    open++;
                } else if (((TaskDTO) task).getProcessingStatus() == TaskStatus.LOCKED) {
                    locked++;
                } else {
                    inProcessing++;
                }
            } else {
                throw new IllegalArgumentException("List should contain tasks as Bean or DTO!");
            }
        }

        results.put(CLOSED, closed);
        results.put(IN_PROCESSING, inProcessing);
        results.put(OPEN, open);
        results.put(LOCKED, locked);

        if ((open + inProcessing + closed + locked) == 0) {
            results.put(LOCKED, 1);
        }

        return results;
    }

    /**
     * Get full text file path.
     *
     * @param process
     *            object
     * @return path as a String to the full text file
     */
    public String getFulltextFilePath(Process process) {
        return getProcessDataDirectory(process) + "/fulltext.xml";
    }

    /**
     * Read metadata file.
     *
     * @param process
     *            object
     * @return filer format
     */
    public FileformatInterface readMetadataFile(Process process)
            throws ReadException, IOException, PreferencesException {
        URI metadataFileUri = serviceManager.getFileService().getMetadataFilePath(process);
        if (!checkForMetadataFile(process)) {
            throw new IOException(Helper.getTranslation("metadataFileNotFound") + " " + metadataFileUri);
        }

        // check the format of the metadata - METS, XStream or RDF
        String type = MetadatenHelper.getMetaFileType(metadataFileUri);
        logger.debug("current meta.xml file type for id {}: {}", process.getId(), type);

        FileformatInterface ff = determineFileFormat(type, process);
        try {
            ff.read(serviceManager.getFileService().getFile(metadataFileUri).toString());
        } catch (ReadException e) {
            if (e.getMessage().startsWith("Parse error at line -1")) {
                Helper.setErrorMessage("metadataCorrupt", logger, e);
            } else {
                throw e;
            }
        }
        return ff;
    }

    private FileformatInterface determineFileFormat(String type, Process process) throws PreferencesException {
        FileformatInterface fileFormat;
        RulesetService rulesetService = serviceManager.getRulesetService();

        switch (type) {
            case "metsmods":
                fileFormat = UghImplementation.INSTANCE
                        .createMetsModsImportExport(rulesetService.getPreferences(process.getRuleset()));
                break;
            case "mets":
                fileFormat = UghImplementation.INSTANCE
                        .createMetsMods(rulesetService.getPreferences(process.getRuleset()));
                break;
            case "xstream":
                fileFormat = UghImplementation.INSTANCE
                        .createXStream(rulesetService.getPreferences(process.getRuleset()));
                break;
            default:
                fileFormat = UghImplementation.INSTANCE
                        .createRDFFile(rulesetService.getPreferences(process.getRuleset()));
                break;
        }
        return fileFormat;
    }

    private boolean checkForMetadataFile(Process process) {
        return fileService.fileExist(fileService.getMetadataFilePath(process));
    }

    /**
     * Read metadata as template file.
     *
     * @param process
     *            object
     * @return file format
     */
    public FileformatInterface readMetadataAsTemplateFile(Process process)
            throws ReadException, IOException, PreferencesException {
        URI processSubTypeURI = fileService.getProcessSubTypeURI(process, ProcessSubType.TEMPLATE, null);
        if (fileService.fileExist(processSubTypeURI)) {
            String type = MetadatenHelper.getMetaFileType(processSubTypeURI);
            logger.debug("current template.xml file type: {}", type);
            FileformatInterface ff = determineFileFormat(type, process);
            String processSubTypePath = fileService.getFile(processSubTypeURI).getAbsolutePath();
            ff.read(processSubTypePath);
            return ff;
        } else {
            throw new IOException("File does not exist: " + processSubTypeURI);
        }
    }

    /**
     * Check if there is one task in edit mode, where the user has the rights to
     * write to image folder.
     *
     * @param process
     *            bean object
     * @return true or false
     */
    public boolean isImageFolderInUse(Process process) {
        for (Task task : process.getTasks()) {
            if (task.getProcessingStatusEnum() == TaskStatus.INWORK && task.isTypeImagesWrite()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if there is one task in edit mode, where the user has the rights to
     * write to image folder.
     *
     * @param process
     *            DTO object
     * @return true or false
     */
    public boolean isImageFolderInUse(ProcessDTO process) {
        for (TaskDTO task : process.getTasks()) {
            if (task.getProcessingStatus() == TaskStatus.INWORK && task.isTypeImagesWrite()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get user of task in edit mode with rights to write to image folder.
     */
    public User getImageFolderInUseUser(Process process) {
        for (Task task : process.getTasks()) {
            if (task.getProcessingStatusEnum() == TaskStatus.INWORK && task.isTypeImagesWrite()) {
                return task.getProcessingUser();
            }
        }
        return null;
    }

    /**
     * Download docket for given process.
     *
     * @param process
     *            object
     * @throws IOException
     *             when xslt file could not be loaded, or write to output failed
     */
    public void downloadDocket(Process process) throws IOException {
        logger.debug("generate docket for process with id {}", process.getId());
        URI rootPath = Paths.get(ConfigCore.getParameter("xsltFolder")).toUri();
        URI xsltFile;
        if (process.getDocket() != null) {
            xsltFile = serviceManager.getFileService().createResource(rootPath, process.getDocket().getFile());
            if (!fileService.fileExist(xsltFile)) {
                Helper.setErrorMessage("docketMissing");
            }
        } else {
            xsltFile = serviceManager.getFileService().createResource(rootPath, "docket.xsl");
        }
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (!facesContext.getResponseComplete()) {
            // write run note to servlet output stream
            DocketInterface module = initialiseDocketModule();

            File file = module.generateDocket(getDocketData(process), xsltFile);
            writeToOutputStream(facesContext, file, process.getTitle() + ".pdf");
        }
    }

    /**
     * Writes a multi page docket for a list of processes to an output stream.
     *
     * @param processes
     *            The list of processes
     * @throws IOException
     *             when xslt file could not be loaded, or write to output failed
     */
    public void downloadDocket(List<Process> processes) throws IOException {
        logger.debug("generate docket for processes {}", processes);
        URI rootPath = Paths.get(ConfigCore.getParameter("xsltFolder")).toUri();
        URI xsltFile = serviceManager.getFileService().createResource(rootPath, "docket_multipage.xsl");
        FacesContext facesContext = FacesContext.getCurrentInstance();
        if (!facesContext.getResponseComplete()) {
            DocketInterface module = initialiseDocketModule();
            File file = module.generateMultipleDockets(serviceManager.getProcessService().getDocketData(processes),
                xsltFile);

            writeToOutputStream(facesContext, file, "batch_docket.pdf");
        }
    }

    /**
     * Good explanation how it should be implemented:
     * https://stackoverflow.com/a/9394237/2701807.
     * 
     * @param facesContext
     *            context
     * @param file
     *            temporal file which contains content to save
     * @param fileName
     *            name of the new docket file
     */
    private void writeToOutputStream(FacesContext facesContext, File file, String fileName) throws IOException {
        ExternalContext externalContext = facesContext.getExternalContext();
        externalContext.responseReset();

        String contentType = externalContext.getMimeType(fileName);
        externalContext.setResponseContentType(contentType);
        externalContext.setResponseHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");

        try (OutputStream outputStream = externalContext.getResponseOutputStream();
                FileInputStream fileInputStream = new FileInputStream(file)) {
            byte[] bytes = IOUtils.toByteArray(fileInputStream);
            outputStream.write(bytes);
            outputStream.flush();
        }
        facesContext.responseComplete();
    }

    private DocketInterface initialiseDocketModule() {
        KitodoServiceLoader<DocketInterface> loader = new KitodoServiceLoader<>(DocketInterface.class);
        return loader.loadModule();
    }

    /**
     * Get method from name.
     *
     * @param methodName
     *            string
     * @param process
     *            object
     * @return method from name
     */
    public URI getMethodFromName(String methodName, Process process) {
        java.lang.reflect.Method method;
        try {
            method = this.getClass().getMethod(methodName);
            Object o = method.invoke(this);
            return (URI) o;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException
                | SecurityException e) {
            logger.debug("exception: " + e);
        }
        try {
            URI folder = this.getImagesTifDirectory(false, process);
            String folderName = fileService.getFileName(folder);
            folderName = folderName.substring(0, folderName.lastIndexOf('_'));
            folderName = folderName + "_" + methodName;
            folder = fileService.renameFile(folder, folderName);
            if (fileService.fileExist(folder)) {
                return folder;
            }
        } catch (IOException ex) {
            logger.debug("exception: " + ex);
        }
        return null;
    }

    /**
     * The addMessageToWikiField() method is a helper method which composes the
     * new wiki field using a StringBuilder. The message is encoded using HTML
     * entities to prevent certain characters from playing merry havoc when the
     * message box shall be rendered in a browser later.
     *
     * @param message
     *            the message to append
     */
    public Process addToWikiField(String message, Process process) {
        StringBuilder composer = new StringBuilder();
        if (process.getWikiField() != null && process.getWikiField().length() > 0) {
            composer.append(process.getWikiField());
            composer.append("\r\n");
        }
        composer.append("<p>");
        composer.append(StringEscapeUtils.escapeHtml(message));
        composer.append("</p>");
        process.setWikiField(composer.toString());

        return process;
    }

    /**
     * Sets new value for wiki field.
     *
     * @param wikiField
     *            string
     * @param process
     *            object
     */
    public void setWikiField(String wikiField, Process process) {
        process.setWikiField(wikiField);
    }

    /**
     * The method addToWikiField() adds a message with a given level to the wiki
     * field of the process. Four level strings will be recognized and result in
     * different colors:
     *
     * <dl>
     * <dt><code>debug</code></dt>
     * <dd>gray</dd>
     * <dt><code>error</code></dt>
     * <dd>red</dd>
     * <dt><code>user</code></dt>
     * <dd>green</dd>
     * <dt><code>warn</code></dt>
     * <dd>orange</dd>
     * <dt><i>any other value</i></dt>
     * <dd>blue</dd>
     * <dt>
     *
     * @param level
     *            message colour, one of: "debug", "error", "info", "user" or
     *            "warn"; any other value defaults to "info"
     * @param message
     *            text
     */
    public String addToWikiField(String level, String message, Process process) {
        return WikiFieldHelper.getWikiMessage(process, process.getWikiField(), level, message);
    }

    /**
     * The method addToWikiField() adds a message signed by the given user to
     * the wiki field of the process.
     *
     * @param user
     *            to sign the message with
     * @param message
     *            to print
     */
    public void addToWikiField(User user, String message, Process process) {
        String text = message + " (" + user.getSurname() + ")";
        addToWikiField("user", text, process);
    }

    /**
     * The method createProcessDirs() starts creation of directories configured
     * by parameter processDirs within kitodo_config.properties
     */
    public void createProcessDirs(Process process) throws IOException {
        String[] processDirs = ConfigCore.getStringArrayParameter(Parameters.PROCESS_DIRS);

        for (String processDir : processDirs) {
            fileService.createDirectory(this.getProcessDataDirectory(process),
                processDir.replace(PROCESS_TITLE, process.getTitle()));
        }
    }

    /**
     * The function getDigitalDocument() returns the digital act of this
     * process.
     *
     * @return the digital act of this process
     * @throws PreferencesException
     *             if the no node corresponding to the file format is available
     *             in the rule set configured
     * @throws ReadException
     *             if the meta data file cannot be read
     * @throws IOException
     *             if creating the process directory or reading the meta data
     *             file fails
     */
    public DigitalDocumentInterface getDigitalDocument(Process process)
            throws PreferencesException, ReadException, IOException {
        return readMetadataFile(process).getDigitalDocument();
    }

    /**
     * Filter for correction / solution messages.
     *
     * @param lpe
     *            List of process properties
     * @return List of filtered correction / solution messages
     */
    protected List<PropertyDTO> filterForCorrectionSolutionMessages(List<PropertyDTO> lpe) {
        ArrayList<PropertyDTO> filteredList = new ArrayList<>();
        List<String> listOfTranslations = new ArrayList<>();
        String propertyTitle;

        listOfTranslations.add("Korrektur notwendig");
        listOfTranslations.add("Korrektur durchgefuehrt");
        listOfTranslations.add(Helper.getTranslation("correctionNecessary"));
        listOfTranslations.add(Helper.getTranslation("correctionPerformed"));

        if (lpe.isEmpty()) {
            return filteredList;
        }

        // filtering for correction and solution messages
        for (PropertyDTO property : lpe) {
            propertyTitle = property.getTitle();
            if (listOfTranslations.contains(propertyTitle)) {
                filteredList.add(property);
            }
        }
        return filteredList;
    }

    /**
     * Filter and sort after creation date list of process properties for
     * correction and solution messages.
     *
     * @return list of ProcessProperty objects
     */
    public List<PropertyDTO> getSortedCorrectionSolutionMessages(ProcessDTO process) {
        List<PropertyDTO> filteredList;
        List<PropertyDTO> properties = process.getProperties();

        if (properties.isEmpty()) {
            return new ArrayList<>();
        }

        filteredList = filterForCorrectionSolutionMessages(properties);

        if (filteredList.size() > 1) {
            filteredList.sort(
                Comparator.comparing(PropertyDTO::getCreationDate, Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        return new ArrayList<>(filteredList);
    }

    /**
     * Find amount of processes for given title.
     *
     * @param title
     *            as String
     * @return amount as Long
     */
    public Long findNumberOfProcessesWithTitle(String title) throws DataException {
        return count(createSimpleQuery(ProcessTypeField.TITLE.getKey(), title, true, Operator.AND).toString());
    }

    /**
     * Reads the metadata File.
     *
     * @param metadataFile
     *            The given metadataFile.
     * @param prefs
     *            The Preferences
     * @return The fileFormat.
     */
    public FileformatInterface readMetadataFile(URI metadataFile, PrefsInterface prefs)
            throws IOException, PreferencesException, ReadException {
        /* prüfen, welches Format die Metadaten haben (Mets, xstream oder rdf */
        String type = MetadatenHelper.getMetaFileType(metadataFile);
        FileformatInterface ff;
        switch (type) {
            case "metsmods":
                ff = UghImplementation.INSTANCE.createMetsModsImportExport(prefs);
                break;
            case "mets":
                ff = UghImplementation.INSTANCE.createMetsMods(prefs);
                break;
            case "xstream":
                ff = UghImplementation.INSTANCE.createXStream(prefs);
                break;
            default:
                ff = UghImplementation.INSTANCE.createRDFFile(prefs);
                break;
        }
        ff.read(ConfigCore.getKitodoDataDirectory() + metadataFile.getPath());

        return ff;
    }

    /**
     * DMS-Export to a desired location.
     *
     * @param process
     *            object
     * @param exportWithImages
     *            true or false
     * @param exportFullText
     *            true or false
     * @return true or false
     */

    public boolean startDmsExport(Process process, boolean exportWithImages, boolean exportFullText)
            throws IOException, PreferencesException, WriteException, JAXBException {
        PrefsInterface preferences = serviceManager.getRulesetService().getPreferences(process.getRuleset());
        String atsPpnBand = process.getTitle();

        // read document
        FileformatInterface gdzfile = readDocument(preferences, process);
        if (Objects.isNull(gdzfile)) {
            return false;
        }

        trimAllMetadata(gdzfile.getDigitalDocument().getLogicalDocStruct());

        // validate metadata
        if (ConfigCore.getBooleanParameter(Parameters.USE_META_DATA_VALIDATION)
                && !serviceManager.getMetadataValidationService().validate(gdzfile, preferences, process)) {
            return false;
        }

        Project project = process.getProject();

        // prepare place for save and download
        URI targetDirectory = new File(project.getDmsImportImagesPath()).toURI();
        URI userHome = targetDirectory;

        // if necessary, create an operation folder
        if (project.isDmsImportCreateProcessFolder()) {
            targetDirectory = userHome.resolve(File.separator + process.getTitle());
            boolean created = createOperationDirectory(userHome, process);
            if (!created) {
                return false;
            }
        }

        // download images
        try {
            if (exportWithImages) {
                imageDownload(process, userHome, atsPpnBand, DIRECTORY_SUFFIX);
                fulltextDownload(process, userHome, atsPpnBand);
            } else if (exportFullText) {
                fulltextDownload(process, userHome, atsPpnBand);
            }

            directoryDownload(process, targetDirectory);
        } catch (RuntimeException e) {
            Helper.setErrorMessage(ERROR_EXPORT, new Object[] {process.getTitle() }, logger, e);
            return false;
        }

        /*
         * zum Schluss Datei an gewünschten Ort exportieren entweder direkt in
         * den Import-Ordner oder ins Benutzerhome anschliessend den
         * Import-Thread starten
         */
        if (project.isUseDmsImport()) {
            if (MetadataFormat.findFileFormatsHelperByName(project.getFileFormatDmsExport()) == MetadataFormat.METS) {
                // if METS, then write by writeMetsFile...
                writeMetsFile(process, userHome + File.separator + atsPpnBand + ".xml", gdzfile, false);
            } else {
                // ...if not, just write a Fileformat
                gdzfile.write(userHome + File.separator + atsPpnBand + ".xml");
            }

            // if necessary, METS and RDF should be written in the export
            if (MetadataFormat
                    .findFileFormatsHelperByName(project.getFileFormatDmsExport()) == MetadataFormat.METS_AND_RDF) {
                writeMetsFile(process, userHome + File.separator + atsPpnBand + ".mets.xml", gdzfile, false);
            }

            Helper.setMessage(process.getTitle() + ": ", "DMS-Export started");

            if (!ConfigCore.getBooleanParameter("exportWithoutTimeLimit") && project.isDmsImportCreateProcessFolder()) {
                // again remove success folder
                File successFile = new File(project.getDmsImportSuccessPath() + File.separator + process.getTitle());
                fileService.delete(successFile.toURI());
            }
        }
        return true;
    }

    private FileformatInterface readDocument(PrefsInterface preferences, Process process)
            throws IOException, PreferencesException {
        FileformatInterface gdzFile;
        FileformatInterface newFile;
        try {
            URI metadataPath = fileService.getMetadataFilePath(process);
            gdzFile = readMetadataFile(metadataPath, preferences);
            switch (MetadataFormat.findFileFormatsHelperByName(process.getProject().getFileFormatDmsExport())) {
                case METS:
                    newFile = UghImplementation.INSTANCE.createMetsModsImportExport(preferences);
                    break;
                case METS_AND_RDF:
                default:
                    newFile = UghImplementation.INSTANCE.createRDFFile(preferences);
                    break;
            }
            newFile.setDigitalDocument(gdzFile.getDigitalDocument());
            gdzFile = newFile;
        } catch (ReadException | RuntimeException e) {
            Helper.setErrorMessage(ERROR_EXPORT, new Object[] {process.getTitle() }, logger, e);
            return null;
        }

        if (handleExceptionsForConfiguration(newFile, process)) {
            return null;
        }
        return gdzFile;
    }

    private boolean createOperationDirectory(URI userHome, Process process) throws IOException {
        Project project = process.getProject();
        // remove old import folder
        if (!fileService.delete(userHome)) {
            Helper.setErrorMessage(Helper.getTranslation(ERROR_EXPORT, Collections.singletonList(process.getTitle())),
                Helper.getTranslation(EXPORT_DIR_DELETE, Collections.singletonList("Import")));
            return false;
        }
        // remove old success folder
        File successFile = new File(project.getDmsImportSuccessPath() + File.separator + process.getTitle());
        if (!fileService.delete(successFile.toURI())) {
            Helper.setErrorMessage(Helper.getTranslation(ERROR_EXPORT, Collections.singletonList(process.getTitle())),
                Helper.getTranslation(EXPORT_DIR_DELETE, Collections.singletonList("Success")));
            return false;
        }
        // remove old error folder
        File errorFile = new File(project.getDmsImportErrorPath() + File.separator + process.getTitle());
        if (!fileService.delete(errorFile.toURI())) {
            Helper.setErrorMessage(Helper.getTranslation(ERROR_EXPORT, Collections.singletonList(process.getTitle())),
                Helper.getTranslation(EXPORT_DIR_DELETE, Collections.singletonList("Error")));
            return false;
        }

        if (!fileService.fileExist(userHome)) {
            fileService.createDirectory(userHome, File.separator + process.getTitle());
        }
        return true;
    }

    /**
     * Method for avoiding redundant code for exception handling. //TODO: should
     * this exceptions be handled that way?
     *
     * @param newFile
     *            as Fileformat
     * @param process
     *            as Process object
     * @return false if no exception appeared
     */
    public boolean handleExceptionsForConfiguration(FileformatInterface newFile, Process process) {
        Optional<String> rules = ConfigCore.getOptionalString(Parameters.COPY_DATA_ON_EXPORT);
        if (rules.isPresent()) {
            try {
                new DataCopier(rules.get()).process(new CopierData(newFile, process));
            } catch (ConfigurationException e) {
                Helper.setErrorMessage("dataCopier.syntaxError", logger, e);
                return true;
            } catch (RuntimeException e) {
                Helper.setErrorMessage("dataCopier.runtimeException", logger, e);
                return true;
            }
        }
        return false;
    }

    /**
     * Run through all metadata and children of given docStruct to trim the
     * strings calls itself recursively.
     *
     * @param docStruct
     *            metadata to be trimmed
     */
    private void trimAllMetadata(DocStructInterface docStruct) {
        // trim all metadata values
        if (docStruct.getAllMetadata() != null) {
            for (MetadataInterface md : docStruct.getAllMetadata()) {
                if (md.getValue() != null) {
                    md.setStringValue(md.getValue().trim());
                }
            }
        }

        // run through all children of docStruct
        if (docStruct.getAllChildren() != null) {
            for (DocStructInterface child : docStruct.getAllChildren()) {
                trimAllMetadata(child);
            }
        }
    }

    /**
     * Download full text.
     *
     * @param userHome
     *            safe file
     * @param atsPpnBand
     *            String
     */
    private void fulltextDownload(Process process, URI userHome, String atsPpnBand) throws IOException {
        downloadSources(process, userHome, atsPpnBand);
        downloadOCR(process, userHome, atsPpnBand);
    }

    private void downloadSources(Process process, URI userHome, String atsPpnBand) throws IOException {
        // download sources
        URI sources = fileService.getSourceDirectory(process);
        if (fileService.fileExist(sources) && !fileService.getSubUris(sources).isEmpty()) {
            URI destination = userHome.resolve(File.separator + atsPpnBand + "_src");
            if (!fileService.fileExist(destination)) {
                fileService.createDirectory(userHome, atsPpnBand + "_src");
            }
            List<URI> files = fileService.getSubUris(sources);
            for (URI file : files) {
                if (fileService.isFile(file)) {
                    URI targetDirectory = destination
                            .resolve(File.separator + fileService.getFileNameWithExtension(file));
                    fileService.copyFile(file, targetDirectory);
                }
            }
        }
    }

    private void downloadOCR(Process process, URI userHome, String atsPpnBand) throws IOException {
        URI ocr = fileService.getOcrDirectory(process);
        if (fileService.fileExist(ocr)) {
            List<URI> folder = fileService.getSubUris(ocr);
            for (URI dir : folder) {
                if (fileService.isDirectory(dir) && !fileService.getSubUris(dir).isEmpty()
                        && fileService.getFileName(dir).contains("_")) {
                    String suffix = fileService.getFileNameWithExtension(dir)
                            .substring(fileService.getFileNameWithExtension(dir).lastIndexOf('_'));
                    URI destination = userHome.resolve(File.separator + atsPpnBand + suffix);
                    if (!fileService.fileExist(destination)) {
                        fileService.createDirectory(userHome, atsPpnBand + suffix);
                    }
                    List<URI> files = fileService.getSubUris(dir);
                    for (URI file : files) {
                        if (fileService.isFile(file)) {
                            URI target = destination
                                    .resolve(File.separator + fileService.getFileNameWithExtension(file));
                            fileService.copyFile(file, target);
                        }
                    }
                }
            }
        }
    }

    /**
     * Download image.
     *
     * @param process
     *            process object
     * @param userHome
     *            safe file
     * @param atsPpnBand
     *            String
     * @param ordnerEndung
     *            String
     */
    public void imageDownload(Process process, URI userHome, String atsPpnBand, final String ordnerEndung)
            throws IOException {

        Project project = process.getProject();
        /*
         * den Ausgangspfad ermitteln
         */
        URI tifOrdner = getImagesTifDirectory(true, process);

        /*
         * jetzt die Ausgangsordner in die Zielordner kopieren
         */
        if (fileService.fileExist(tifOrdner) && !fileService.getSubUris(tifOrdner).isEmpty()) {
            URI zielTif = userHome.resolve(File.separator + atsPpnBand + ordnerEndung);

            /* bei Agora-Import einfach den Ordner anlegen */
            if (project.isUseDmsImport()) {
                if (!fileService.fileExist(zielTif)) {
                    fileService.createDirectory(userHome, atsPpnBand + ordnerEndung);
                }
            } else {
                /*
                 * wenn kein Agora-Import, dann den Ordner mit
                 * Benutzerberechtigung neu anlegen
                 */
                User user = serviceManager.getUserService().getAuthenticatedUser();
                try {
                    fileService.createDirectoryForUser(zielTif, user.getLogin());
                } catch (RuntimeException e) {
                    Helper.setErrorMessage(ERROR_EXPORT, "could not create destination directory", logger, e);
                }
            }

            // jetzt den eigentlichen Kopiervorgang
            List<URI> dateien = fileService.getSubUris(Helper.dataFilter, tifOrdner);
            for (URI file : dateien) {
                if (fileService.isFile(file)) {
                    URI target = zielTif.resolve(File.separator + fileService.getFileNameWithExtension(file));
                    fileService.copyFile(file, target);
                }
            }
        }
    }

    /**
     * write MetsFile to given Path.
     *
     * @param process
     *            the Process to use
     * @param targetFileName
     *            the filename where the metsfile should be written
     * @param gdzfile
     *            the FileFormat-Object to use for Mets-Writing
     */
    protected boolean writeMetsFile(Process process, String targetFileName, FileformatInterface gdzfile,
            boolean writeLocalFilegroup) throws PreferencesException, IOException, WriteException, JAXBException {
        PrefsInterface preferences = serviceManager.getRulesetService().getPreferences(process.getRuleset());
        Project project = process.getProject();
        MetsModsImportExportInterface mm = UghImplementation.INSTANCE.createMetsModsImportExport(preferences);
        mm.setWriteLocal(writeLocalFilegroup);
        URI imageFolderPath = fileService.getImagesDirectory(process);
        File imageFolder = new File(imageFolderPath);
        /*
         * before creating mets file, change relative path to absolute -
         */
        DigitalDocumentInterface dd = gdzfile.getDigitalDocument();
        if (dd.getFileSet() == null) {
            Helper.setErrorMessage(process.getTitle() + ": digital document does not contain images; aborting");
            return false;
        }

        /*
         * get the topstruct element of the digital document depending on anchor
         * property
         */
        DocStructInterface topElement = dd.getLogicalDocStruct();
        if (preferences.getDocStrctTypeByName(topElement.getDocStructType().getName()).getAnchorClass() != null) {
            if (topElement.getAllChildren() == null || topElement.getAllChildren().isEmpty()) {
                throw new PreferencesException(process.getTitle()
                        + ": the topstruct element is marked as anchor, but does not have any children for "
                        + "physical docstrucs");
            } else {
                topElement = topElement.getAllChildren().get(0);
            }
        }

        /*
         * if the top element does not have any image related, set them all
         */
        if (topElement.getAllToReferences("logical_physical") == null
                || topElement.getAllToReferences("logical_physical").isEmpty()) {
            if (dd.getPhysicalDocStruct() != null && dd.getPhysicalDocStruct().getAllChildren() != null) {
                Helper.setMessage(process.getTitle()
                        + ": topstruct element does not have any referenced images yet; temporarily adding them "
                        + "for mets file creation");
                for (DocStructInterface mySeitenDocStruct : dd.getPhysicalDocStruct().getAllChildren()) {
                    topElement.addReferenceTo(mySeitenDocStruct, "logical_physical");
                }
            } else {
                Helper.setErrorMessage(process.getTitle() + ": could not find any referenced images, export aborted");
                return false;
            }
        }

        for (ContentFileInterface cf : dd.getFileSet().getAllFiles()) {
            String location = cf.getLocation();
            // If the file's location string shoes no sign of any protocol,
            // use the file protocol.
            if (!location.contains("://")) {
                location = "file://" + location;
            }
            String url = new URL(location).getFile();
            File f = new File(!url.startsWith(imageFolder.toURI().toURL().getPath()) ? imageFolder : null, url);
            cf.setLocation(f.toURI().toString());
        }

        mm.setDigitalDocument(dd);

        /*
         * wenn Filegroups definiert wurden, werden diese jetzt in die
         * Metsstruktur übernommen
         */
        // Replace all paths with the given VariableReplacer, also the file
        // group paths!
        VariableReplacer variables = new VariableReplacer(mm.getDigitalDocument(), preferences, process, null);
        List<Folder> folders = project.getFolders();
        for (Folder folder : folders) {
            // check if source files exists
            if (folder.getLinkingMode().equals(LinkingMode.EXISTING)) {
                URI folderUri = new File(folder.getRelativePath()).toURI();
                if (fileService.fileExist(folderUri)
                        && !serviceManager.getFileService().getSubUris(folderUri).isEmpty()) {
                    mm.getDigitalDocument().getFileSet()
                            .addVirtualFileGroup(prepareVirtualFileGroup(folder, variables));
                }
            } else if (!folder.getLinkingMode().equals(LinkingMode.NO)) {
                mm.getDigitalDocument().getFileSet().addVirtualFileGroup(prepareVirtualFileGroup(folder, variables));
            }
        }

        // Replace rights and digiprov entries.
        mm.setRightsOwner(variables.replace(project.getMetsRightsOwner()));
        mm.setRightsOwnerLogo(variables.replace(project.getMetsRightsOwnerLogo()));
        mm.setRightsOwnerSiteURL(variables.replace(project.getMetsRightsOwnerSite()));
        mm.setRightsOwnerContact(variables.replace(project.getMetsRightsOwnerMail()));
        mm.setDigiprovPresentation(variables.replace(project.getMetsDigiprovPresentation()));
        mm.setDigiprovReference(variables.replace(project.getMetsDigiprovReference()));
        mm.setDigiprovPresentationAnchor(variables.replace(project.getMetsDigiprovPresentationAnchor()));
        mm.setDigiprovReferenceAnchor(variables.replace(project.getMetsDigiprovReferenceAnchor()));

        mm.setPurlUrl(variables.replace(project.getMetsPurl()));
        mm.setContentIDs(variables.replace(project.getMetsContentIDs()));

        // Set mets pointers. MetsPointerPathAnchor or mptrAnchorUrl is the
        // pointer used to point to the superordinate (anchor) file, that is
        // representing a “virtual” group such as a series. Several anchors
        // pointer paths can be defined/ since it is possible to define several
        // levels of superordinate structures (such as the complete edition of
        // a daily newspaper, one year ouf of that edition, …)
        String anchorPointersToReplace = project.getMetsPointerPath();
        mm.setMptrUrl(null);
        for (String anchorPointerToReplace : anchorPointersToReplace.split(Project.ANCHOR_SEPARATOR)) {
            String anchorPointer = variables.replace(anchorPointerToReplace);
            mm.setMptrUrl(anchorPointer);
        }

        // metsPointerPathAnchor or mptrAnchorUrl is the pointer used to point
        // from the (lowest) superordinate (anchor) file to the lowest level
        // file (the non-anchor file).
        String anchor = project.getMetsPointerPathAnchor();
        String pointer = variables.replace(anchor);
        mm.setMptrAnchorUrl(pointer);

        try {
            // TODO andere Dateigruppen nicht mit image Namen ersetzen
            List<URI> images = getDataFiles(process);
            List<String> imageStrings = new ArrayList<>();
            for (URI image : images) {
                imageStrings.add(image.getPath());
            }
            int sizeOfPagination = dd.getPhysicalDocStruct().getAllChildren().size();
            int sizeOfImages = images.size();
            if (sizeOfPagination == sizeOfImages) {
                dd.overrideContentFiles(imageStrings);
            } else {
                Helper.setErrorMessage("imagePaginationError", new Object[] {sizeOfPagination, sizeOfImages });
                return false;
            }
        } catch (IndexOutOfBoundsException | InvalidImagesException e) {
            logger.error(e.getMessage(), e);
        }
        mm.write(targetFileName);
        Helper.setMessage(process.getTitle() + ": ", "exportFinished");
        return true;
    }

    private VirtualFileGroupInterface prepareVirtualFileGroup(Folder folder, VariableReplacer variableReplacer)
            throws IOException, JAXBException {
        VirtualFileGroupInterface virtualFileGroup = UghImplementation.INSTANCE.createVirtualFileGroup();
        virtualFileGroup.setName(folder.getFileGroup());
        virtualFileGroup.setPathToFiles(variableReplacer.replace(folder.getUrlStructure()));
        virtualFileGroup.setMimetype(folder.getMimeType());
        if (FileFormatsConfig.getFileFormat(folder.getMimeType()).isPresent()) {
            virtualFileGroup.setFileSuffix(
                folder.getUGHTail(FileFormatsConfig.getFileFormat(folder.getMimeType()).get().getExtension(false)));
        }
        return virtualFileGroup;
    }

    /**
     * Set showClosedProcesses.
     *
     * @param showClosedProcesses
     *            as boolean
     */
    public void setShowClosedProcesses(boolean showClosedProcesses) {
        this.showClosedProcesses = showClosedProcesses;
    }

    /**
     * Set showInactiveProjects.
     *
     * @param showInactiveProjects
     *            as boolean
     */
    public void setShowInactiveProjects(boolean showInactiveProjects) {
        this.showInactiveProjects = showInactiveProjects;
    }

    /**
     * Get data files.
     *
     * @return String
     */
    public List<URI> getDataFiles(Process process) throws InvalidImagesException {
        URI dir;
        try {
            dir = getImagesTifDirectory(true, process);
        } catch (IOException | RuntimeException e) {
            throw new InvalidImagesException(e);
        }

        List<URI> dataList = new ArrayList<>();
        List<URI> files = fileService.getSubUris(Helper.dataFilter, dir);
        if (!files.isEmpty()) {
            dataList.addAll(files);
            Collections.sort(dataList);
        }
        return dataList;
    }

    /**
     * Starts copying all directories configured in kitodo_config.properties
     * parameter "processDirs" to export folder.
     *
     * @param myProcess
     *            the process object
     * @param targetDirectory
     *            the destination directory
     */
    private void directoryDownload(Process myProcess, URI targetDirectory) throws IOException {
        String[] processDirs = ConfigCore.getStringArrayParameter(Parameters.PROCESS_DIRS);

        for (String processDir : processDirs) {
            URI sourceDirectory = URI.create(getProcessDataDirectory(myProcess).toString() + "/"
                    + processDir.replace(PROCESS_TITLE, myProcess.getTitle()));
            URI destinationDirectory = URI
                    .create(targetDirectory.toString() + "/" + processDir.replace(PROCESS_TITLE, myProcess.getTitle()));

            if (fileService.isDirectory(sourceDirectory)) {
                fileService.copyFile(sourceDirectory, destinationDirectory);
            }
        }
    }

    /**
     * Creates a List of Docket data for the given processes.
     *
     * @param processes
     *            the process to create the docket data for.
     * @return A List of DocketData objects
     */
    public List<DocketData> getDocketData(List<Process> processes) {
        List<DocketData> docketData = new ArrayList<>();
        for (Process process : processes) {
            docketData.add(getDocketData(process));
        }
        return docketData;
    }

    /**
     * Creates the DocketData for a given Process.
     *
     * @param process
     *            The process to create the docket data for.
     * @return The DocketData for the process.
     */
    private DocketData getDocketData(Process process) {
        DocketData docketdata = new DocketData();

        docketdata.setCreationDate(process.getCreationDate().toString());
        docketdata.setProcessId(process.getId().toString());
        docketdata.setProcessName(process.getTitle());
        docketdata.setProjectName(process.getProject().getTitle());
        docketdata.setRulesetName(process.getRuleset().getTitle());
        docketdata.setComment(process.getWikiField());

        if (!process.getTemplates().isEmpty() && process.getTemplates().get(0) != null) {
            docketdata.setTemplateProperties(getDocketDataForProperties(process.getTemplates()));
        }
        if (!process.getWorkpieces().isEmpty() && process.getWorkpieces().get(0) != null) {
            docketdata.setWorkpieceProperties(getDocketDataForProperties(process.getWorkpieces()));
        }
        docketdata.setProcessProperties(getDocketDataForProperties(process.getProperties()));

        return docketdata;
    }

    private ArrayList<org.kitodo.api.docket.Property> getDocketDataForProperties(List<Property> properties) {
        ArrayList<org.kitodo.api.docket.Property> propertiesForDocket = new ArrayList<>();
        for (Property property : properties) {
            org.kitodo.api.docket.Property propertyForDocket = new org.kitodo.api.docket.Property();
            propertyForDocket.setId(property.getId());
            propertyForDocket.setTitle(property.getTitle());
            propertyForDocket.setValue(property.getValue());

            propertiesForDocket.add(propertyForDocket);

        }

        return propertiesForDocket;
    }

    /**
     * Find all processes of active projects, sorted according to sort query.
     *
     * @param sort
     *            possible sort query according to which results will be sorted
     * @return the list of sorted processes as ProcessDTO objects
     */
    public List<ProcessDTO> findProcessesOfActiveProjects(String sort) throws DataException {
        return convertJSONObjectsToDTOs(findByActive(true, sort), false);
    }

    /**
     * Find not closed processes sorted according to sort query.
     *
     * @param sort
     *            possible sort query according to which results will be sorted
     *
     * @return the list of sorted processes as ProcessDTO objects
     */
    public List<ProcessDTO> findNotClosedProcessesWithoutTemplates(String sort) throws DataException {
        return convertJSONObjectsToDTOs(findBySortHelperStatus(false, sort), false);
    }

    /**
     * Find processes of open and active projects, sorted according to sort
     * query.
     *
     * @param sort
     *            possible sort query according to which results will be sorted
     * @return the list of sorted processes as ProcessDTO objects
     */
    public List<ProcessDTO> findOpenAndActiveProcessesWithoutTemplates(String sort) throws DataException {
        return convertJSONObjectsToDTOs(findBySortHelperStatusProjectActive(false, true, sort), false);
    }

    /**
     * Find all processes of active projects that are not templates, sorted
     * according to sort query.
     *
     * @param sort
     *            possible sort query according to which results will be sorted
     * @return the list of sorted processes as ProcessDTO objectss
     */
    public List<ProcessDTO> findAllActiveWithoutTemplates(String sort) throws DataException {
        return convertJSONObjectsToDTOs(findByActive(true, sort), false);
    }

    /**
     * Get all active processes.
     *
     * @return A list of all processes as Process objects.
     */
    public List<Process> getActiveProcesses() {
        return dao.getActiveProcesses();
    }
}
