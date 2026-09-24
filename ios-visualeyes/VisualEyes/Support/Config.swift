import Foundation

/// Backend configuration. Mirrors `ApiConfig.java` in the Android app exactly —
/// same Supabase project, same anon key, same Edge Functions. This is the
/// public "anon" key (role `anon`), not a secret; it's safe to embed
/// client-side the same way the Android app already does.
enum Config {
    static let supabaseURL = URL(string: "https://vhwtaboizmwmgtnckugn.supabase.co")!

    static let supabaseAnonKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZod3RhYm9pem13bWd0bmNrdWduIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY3ODMzODMsImV4cCI6MjA5MjM1OTM4M30.Wa51C--MbuxVOZSkJdQEltCwwSV3As9Ww-VAxFpffCg"

    /// The actual Google Cloud API key stays server-side inside the Edge
    /// Function — never shipped in this app, same as the Android build.
    static let googleSttFunctionURL = supabaseURL.appendingPathComponent("functions/v1/google-stt")
    static let googleTtsFunctionURL = supabaseURL.appendingPathComponent("functions/v1/google-tts")

    static var restRPCBaseURL: URL {
        supabaseURL.appendingPathComponent("rest/v1/rpc")
    }

    static var storageBaseURL: URL {
        supabaseURL.appendingPathComponent("storage/v1")
    }
}
