// Proxies Google Cloud Speech-to-Text so the raw API key never ships inside
// the Android app. Forwards the exact same request body the app used to
// send straight to Google, and returns Google's response unchanged — no
// client-side parsing logic needs to change, only the URL/auth.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

const GOOGLE_API_KEY = Deno.env.get("GOOGLE_API_KEY")!;
const GOOGLE_STT_URL = "https://speech.googleapis.com/v1/speech:recognize";

serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  const body = await req.text();

  const upstream = await fetch(`${GOOGLE_STT_URL}?key=${GOOGLE_API_KEY}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body,
  });

  const text = await upstream.text();
  return new Response(text, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
});
