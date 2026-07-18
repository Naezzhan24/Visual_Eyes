package com.example.visualeyes;

public class TtsResponseBuilder {

    public static String buildResponse(NlpProcessor.NlpResult result) {
        if (result == null) {
            return "Sorry, I did not understand the command.";
        }

        switch (result.intent) {
            case "READ_TITLES":
                return "Reading your available learning materials.";

            case "READ_FEATURED_TITLE":
                return "Reading the title of your latest material.";

            case "OPEN_MATERIAL":
                switch (result.target) {
                    case "FIRST":
                        return "Opening the first material.";
                    case "SECOND":
                        return "Opening the second material.";
                    case "THIRD":
                        return "Opening the third material.";
                    case "FEATURED":
                        return "Opening your latest material.";
                    default:
                        return "Opening the selected material.";
                }

            case "STOP":
                return "Stopping now.";

            case "REPEAT":
                return "Repeating the last message.";

            default:
                return "Sorry, I did not understand the command. Please try again.";
        }
    }
}
