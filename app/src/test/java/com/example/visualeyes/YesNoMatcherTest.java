package com.example.visualeyes;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class YesNoMatcherTest {

    private static void assertAnswer(YesNoMatcher.Answer expected, String heard) {
        assertEquals("heard: \"" + heard + "\"", expected, YesNoMatcher.parse(heard));
    }

    @Test public void bareYesWords() {
        for (String s : new String[]{"yes", "Yes.", "yeah", "yep", "yup", "yas", "oo", "opo", "sure", "okay"}) {
            assertAnswer(YesNoMatcher.Answer.YES, s);
        }
    }

    @Test public void bareNoWords() {
        for (String s : new String[]{"no", "No!", "nope", "nah", "hindi", "hinde", "di", "know"}) {
            assertAnswer(YesNoMatcher.Answer.NO, s);
        }
    }

    @Test public void naturalSentencesThatUsedToBeMissed() {
        assertAnswer(YesNoMatcher.Answer.YES, "yeah I can");
        assertAnswer(YesNoMatcher.Answer.YES, "yep I can read it");
        assertAnswer(YesNoMatcher.Answer.YES, "oo kaya ko");
        assertAnswer(YesNoMatcher.Answer.NO,  "nope too small");
        assertAnswer(YesNoMatcher.Answer.NO,  "nah I can't");
    }

    @Test public void negatedCanIsNo() {
        assertAnswer(YesNoMatcher.Answer.NO, "I can't read it");
        assertAnswer(YesNoMatcher.Answer.NO, "cannot");
        assertAnswer(YesNoMatcher.Answer.NO, "I can not read it");
        assertAnswer(YesNoMatcher.Answer.NO, "I don't see it");
        assertAnswer(YesNoMatcher.Answer.NO, "hindi ko mabasa");
    }

    @Test public void lastDecisiveWordWinsWhenStudentCorrectsThemselves() {
        assertAnswer(YesNoMatcher.Answer.YES, "no wait yes");
        assertAnswer(YesNoMatcher.Answer.NO,  "yes no");
    }

    @Test public void partialTranscriptsAreNotDecidedEarlyByCan() {
        // The recognizer streams "can" before "can't"; "can" alone must stay undecided.
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "can");
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "I can");
    }

    @Test public void unrelatedOrEmptySpeechIsUnknown() {
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "");
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "   ");
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, null);
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "what is this");
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "yesterday");
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "now");
        assertAnswer(YesNoMatcher.Answer.UNKNOWN, "note");
    }
}
