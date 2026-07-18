package com.example.visualeyes;

public class LearningMaterial {
    private String id;
    private String title;
    private String subject;
    private String fileUrl;
    private String dateResolved;

    public LearningMaterial(String id, String title, String subject, String fileUrl, String dateResolved) {
        this.id = id;
        this.title = title;
        this.subject = subject;
        this.fileUrl = fileUrl;
        this.dateResolved = dateResolved;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSubject() {
        return subject;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getDateResolved() {
        return dateResolved;
    }
}
