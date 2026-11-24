package com.shidaifufen.todocard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentDataTest {
    @Test fun networkIsDefaultAndRecentContentIsNotClaimedTwice() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("content_options", 0).edit().clear().commit()
        assertTrue(ContentOptions.online(ctx, "quote"))
        assertTrue(ContentOptions.claim(ctx, "quote", "月光照故乡"))
        assertFalse(ContentOptions.claim(ctx, "quote", "月光 照故乡"))
        ContentOptions.setOnline(ctx, "quote", false)
        assertFalse(ContentOptions.online(ctx, "quote"))
    }

    @Test fun quoteUsesActualAuthorAndFallsBackToWorkTitle() {
        val value = ContentData.parseQuote(JSONObject("""{"hitokoto":"原句","from_who":"作者","from":"作品"}"""))
        assertEquals("作者", value.author)
        assertEquals("作品", ContentData.parseQuote(JSONObject("""{"hitokoto":"原句","from_who":null,"from":"作品"}""")).author)
        assertEquals("佚名", ContentData.parseQuote(JSONObject("""{"hitokoto":"原句","from_who":null,"from":null}""")).author)
    }

    @Test fun poemKeepsOriginAuthorAndTranslationTogether() {
        val value = ContentData.parsePoem(JSONObject("""{"data":{"origin":{"title":"静夜思","author":"李白","dynasty":"唐代","content":["床前明月光，疑是地上霜。","举头望明月，低头思故乡。"],"translate":["抬头望月，低头思乡。"]}}}"""))
        assertEquals("静夜思", value.title)
        assertEquals("李白", value.author)
        assertEquals(2, value.lines.size)
        assertEquals("抬头望月，低头思乡。", value.note)
    }

    @Test(expected = IllegalStateException::class)
    fun emptyResponseDoesNotReplaceSavedQuote() {
        ContentData.parseQuote(JSONObject("{}"))
    }
}
