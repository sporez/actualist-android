package com.sporez.actualist.core.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportPayeeNameNormalizationTest {
    @Test
    fun originalKeepsTheName() {
        assertEquals("STARBUCKS COFFEE", ImportPayeeNameNormalization.ORIGINAL.normalize("STARBUCKS COFFEE"))
    }

    @Test
    fun titleCaseCapitalizesWords() {
        assertEquals("Starbucks Coffee", ImportPayeeNameNormalization.TITLE_CASE.normalize("STARBUCKS COFFEE"))
    }

    @Test
    fun titleCaseKeepsSmallWordsLowerCaseUnlessTheyOpenTheName() {
        assertEquals("The Lord of the Rings", ImportPayeeNameNormalization.TITLE_CASE.normalize("the lord of the rings"))
    }

    @Test
    fun titleCaseRestoresSpecialWords() {
        assertEquals("GitHub API", ImportPayeeNameNormalization.TITLE_CASE.normalize("github api"))
    }
}
