package com.zhishiku.kb.app.space.model;

/**
 * 空间行，含调用者在该空间上的两个资格标记。仅 mapper 与 service 之间流转。
 */
public class SpaceRecord {

    private Long id;
    private String code;
    private String name;
    private String description;
    private Boolean archived;
    private Long version;
    private Boolean viewable;
    private Boolean manageable;
    private Long documentCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Boolean getViewable() {
        return viewable;
    }

    public void setViewable(Boolean viewable) {
        this.viewable = viewable;
    }

    public Boolean getManageable() {
        return manageable;
    }

    public void setManageable(Boolean manageable) {
        this.manageable = manageable;
    }

    public Long getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(Long documentCount) {
        this.documentCount = documentCount;
    }
}
