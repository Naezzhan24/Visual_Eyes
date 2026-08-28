package com.example.visualeyes;

public class ApiConfig {

    public static final String SUPABASE_URL = "https://vhwtaboizmwmgtnckugn.supabase.co";

    public static final String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZod3RhYm9pem13bWd0bmNrdWduIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY3ODMzODMsImV4cCI6MjA5MjM1OTM4M30.Wa51C--MbuxVOZSkJdQEltCwwSV3As9Ww-VAxFpffCg";

    // Proxy the actual Google Cloud API key server-side (see
    // supabase/functions/google-stt, google-tts) so it never ships inside
    // the APK, where it would be trivially extractable with `strings`.
    public static final String GOOGLE_STT_FUNCTION = SUPABASE_URL + "/functions/v1/google-stt";
    public static final String GOOGLE_TTS_FUNCTION = SUPABASE_URL + "/functions/v1/google-tts";
}
