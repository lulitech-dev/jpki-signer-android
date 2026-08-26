package dev.lulitech.jpkisigner.data

import dev.lulitech.jpkisigner.jpki.CardProblem
import dev.lulitech.jpkisigner.jpki.PinProblem
import dev.lulitech.jpkisigner.pdf.PdfRejection
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lockout warning is shown from [SignFailure.remainingAttempts], so which
 * failures do and do not carry a count is a safety property, not a detail: a
 * number shown after a failure of unknown outcome would be a guess presented as
 * fact, about the one thing the user cannot afford to be wrong about.
 */
class SignFailureTest {

    @Test
    fun `a wrong pin carries the count the card gave`() {
        assertEquals(
            2,
            SignFailure.Card(CardProblem.WrongPin(remainingAttempts = 2)).remainingAttempts,
        )
    }

    @Test
    fun `a refusal to proceed carries the count it refused on`() {
        assertEquals(1, SignFailure.TooFewAttempts(remaining = 1).remainingAttempts)
    }

    @Test
    fun `a wrong pin the card did not count carries nothing`() {
        assertNull(
            SignFailure.Card(CardProblem.WrongPin(remainingAttempts = null)).remainingAttempts,
        )
    }

    /** The whole point of the case: the attempt may or may not have been spent. */
    @Test
    fun `an interrupted verify carries no count`() {
        assertNull(SignFailure.Card(CardProblem.VerifyOutcomeUnknown).remainingAttempts)
    }

    @Test
    fun `a blocked pin carries no count`() {
        assertNull(SignFailure.Card(CardProblem.PinBlocked).remainingAttempts)
    }

    @Test
    fun `failures that never reached the card carry no count`() {
        assertNull(SignFailure.DocumentMissing.remainingAttempts)
        assertNull(SignFailure.ChangesNotPermitted.remainingAttempts)
        assertNull(
            SignFailure.DocumentUnusable(PdfRejection.UNREADABLE).remainingAttempts,
        )
        assertNull(SignFailure.MalformedPin(PinProblem.NotDigits).remainingAttempts)
        assertNull(SignFailure.Unexpected("something else").remainingAttempts)
    }
}
