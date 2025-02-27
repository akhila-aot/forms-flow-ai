package org.camunda.bpm.extension.hooks.listeners.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.oauth2.sdk.util.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.Expression;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.extension.hooks.listeners.BaseListener;
import org.camunda.bpm.extension.hooks.listeners.data.FormSubmission;
import org.camunda.bpm.extension.hooks.services.FormSubmissionService;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class associates form for user task.
 *
 * @author sumathi.thirumani@aot-technologies.com
 */
@Component
public class FormConnectorListener extends BaseListener implements TaskListener {

    private Expression fields;
    private Expression copyDataIndicator;

    @Autowired
    private FormSubmissionService formSubmissionService;

    @Override
    public void notify(DelegateTask delegateTask) {
        try {
            String submissionId = createSubmission(getFormUrl(delegateTask),getNewFormSubmissionUrl(delegateTask), delegateTask);
            if(StringUtils.isNotBlank(submissionId)) {
                delegateTask.getExecution().setVariable("formUrl", getModifiedFormUrl(delegateTask,submissionId));
            }
        } catch (IOException e) {
           handleException(delegateTask.getExecution(), ExceptionSource.TASK, e);
        }

    }

    /**
     *
     * @param sourceFormUrl
     * @param targetFormUrl
     * @param delegateTask
     * @return
     * @throws JsonProcessingException
     */
    private String createSubmission(String sourceFormUrl, String targetFormUrl,DelegateTask delegateTask) throws IOException {
        String submission = formSubmissionService.readSubmission(sourceFormUrl);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String,Object> superVariables = new HashMap<>();
        List<String> supFields =  this.fields != null && this.fields.getValue(delegateTask) != null ?
                objectMapper.readValue(String.valueOf(this.fields.getValue(delegateTask)),List.class): null;

        for(String entry : supFields) {
            superVariables.put(entry, delegateTask.getExecution().getVariables().get(entry));
        }
        return  formSubmissionService.createSubmission(targetFormUrl, createFormSubmissionData(submission, superVariables, getPropogateData(delegateTask)));
    }

    /**
     *
     * @param submission
     * @param superVariables
     * @param propogateData
     * @return
     */
    private String createFormSubmissionData(String submission, Map<String,Object> superVariables, String propogateData) {
        try {
            JsonNode submissionNode = getObjectMapper().readTree(submission);
            JsonNode dataNode =submissionNode.get("data");
            if("Y".equals(propogateData)) {
                for(Map.Entry<String,Object> entry : superVariables.entrySet()) {
                    ((ObjectNode)dataNode).put(entry.getKey(), getObjectMapper().convertValue(entry.getValue(), JsonNode.class));
                }
                return getObjectMapper().writeValueAsString(new FormSubmission(dataNode));
            } else {
                return getObjectMapper().writeValueAsString(new FormSubmission(getObjectMapper().convertValue(superVariables, JsonNode.class)));
            }


        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return  null;
    }

    /**
     * Get the formID for associated name.
     *
     * @param delegateTask
     * @return
     */
    private String getFormId(DelegateTask delegateTask) throws IOException {
        CamundaProperties camundaProperties = delegateTask.getExecution()
                .getBpmnModelElementInstance()
                .getExtensionElements()
                .getElementsQuery()
                .filterByType(CamundaProperties.class).singleResult();

        List<CamundaProperty> userTaskExtensionProperties = camundaProperties.getCamundaProperties()
                .stream()
                .filter(camundaProperty ->
                        camundaProperty.getCamundaName()
                                .equals(getFormIdProperty()))
                .collect(Collectors.toList());


        if(CollectionUtils.isNotEmpty(userTaskExtensionProperties)) {
            String formName = userTaskExtensionProperties.get(0).getCamundaValue();
            return formSubmissionService.getFormIdByName(StringUtils.substringBefore(getFormUrl(delegateTask),"/form/")+"/"+formName);
        }

        return null;
    }

    /**
     * Defines the form ID property.
     * @return
     */
    private String getFormIdProperty() {
        return "formName";
    }

    /**
     * Returns the data propagation property value.
     *
     * @param delegateTask
     * @return
     */
    private String getPropogateData(DelegateTask delegateTask){
        if(this.copyDataIndicator != null &&
                StringUtils.isNotBlank(String.valueOf(this.copyDataIndicator.getValue(delegateTask)))) {
            return String.valueOf(this.copyDataIndicator.getValue(delegateTask));
        }
        return "N";
    }

    /**
     * Returns Object Mapper Instance
     * @return
     */
    private ObjectMapper getObjectMapper(){
        return new ObjectMapper();
    }

    /**
     * Returns new URL for submission.
     *
     * @param delegateTask
     * @return
     */
    private String getNewFormSubmissionUrl(DelegateTask delegateTask) throws IOException {
        String formUrl = getFormUrl(delegateTask);
        return StringUtils.replace(formUrl, StringUtils.substringBetween(formUrl, "form/", "/submission"), getFormId(delegateTask));
    }

    /**
     * Returns the formURl from execution context
     *
     * @param delegateTask
     * @return
     */
    private String getFormUrl(DelegateTask delegateTask) {
        return String.valueOf(delegateTask.getExecution().getVariables().get("formUrl"));
    }

    /**
     * Returns the updated formUrl with new submission ID.
     *
     * @param delegateTask
     * @param submissionId
     * @return
     */
    private String getModifiedFormUrl(DelegateTask delegateTask, String submissionId) throws IOException {
        String formUrl = StringUtils.substringBefore(getFormUrl(delegateTask),"/form/");
        return formUrl+ "/form/" + getFormId(delegateTask) + "/submission/" + submissionId;
    }

}
