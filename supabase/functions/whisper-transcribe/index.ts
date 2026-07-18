// Proxies OpenAI Whisper transcription so the raw API key never ships inside
// the Android app. The app still builds the same multipart form (file,
// model, language, prompt, temperature) — this just re-posts that same
// form to OpenAI with the real key attached server-side, and returns
// OpenAI's response unchanged.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!;
const WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";

serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  const incomingForm = await req.formData();

  const upstream = await fetch(WHISPER_URL, {
    method: "POST",
    headers: { "Authorization": `Bearer ${OPENAI_API_KEY}` },
    body: incomingForm,
  });

  const text = await upstream.text();
  return new Response(text, {
    status: upstream.status,
    headers: { "Content-Type": "application/json" },
  });
});
