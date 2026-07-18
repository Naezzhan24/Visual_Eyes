package com.example.visualeyes;

public class ApiConfig {

    public static final String SUPABASE_URL = "https://vhwtaboizmwmgtnckugn.supabase.co";

    public static final String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZod3RhYm9pem13bWd0bmNrdWduIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY3ODMzODMsImV4cCI6MjA5MjM1OTM4M30.Wa51C--MbuxVOZSkJdQEltCwwSV3As9Ww-VAxFpffCg";

    public static final String STUDENTS = SUPABASE_URL + "/rest/v1/students";
    public static final String MATERIALS = SUPABASE_URL + "/rest/v1/materials";
    public static final String ACCESSIBILITY_RESULTS = SUPABASE_URL + "/rest/v1/accessibility_results";
    public static final String STUDENT_ACCESS = SUPABASE_URL + "/rest/v1/student_material_access";

    public static final String MATERIAL_FEEDBACKS =
            SUPABASE_URL + "/rest/v1/material_feedbacks";
}
