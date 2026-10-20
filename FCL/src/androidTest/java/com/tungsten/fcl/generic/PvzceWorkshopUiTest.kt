package com.tungsten.fcl.generic

import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.google.gson.JsonParser
import com.tungsten.fcl.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device checks for native state/encryption; not evidence of a real Casdoor login or game execution. */
@RunWith(AndroidJUnit4::class)
class PvzceWorkshopUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun accountAndSearchFieldsSurviveRecreationWithoutMinecraftAccounts() {
        check(context.packageName.endsWith(".debug"))
        context.getSharedPreferences("pvzce_workshop", Context.MODE_PRIVATE).edit().clear().commit()
        val intent = Intent(context, PvzceWorkshopActivity::class.java).putExtra("account", true)
        ActivityScenario.launch<PvzceWorkshopActivity>(intent).use { scenario ->
            var retained: WorkshopViewModel? = null
            scenario.onActivity { activity ->
                retained = ViewModelProvider(activity)[WorkshopViewModel::class.java]
                activity.findViewById<EditText>(R.id.pvzce_api_address).setText("https://workshop.example")
                activity.findViewById<EditText>(R.id.pvzce_workshop_search).setText("中文模组")
                assertNotNull(activity.findViewById<android.view.View>(R.id.pvzce_account_login))
                assertNull(activity.findViewById<android.view.View>(R.id.account))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertSame(retained, ViewModelProvider(activity)[WorkshopViewModel::class.java])
                assertEquals("https://workshop.example", activity.findViewById<EditText>(R.id.pvzce_api_address).text.toString())
                assertEquals("中文模组", activity.findViewById<EditText>(R.id.pvzce_workshop_search).text.toString())
            }
        }
    }

    @Test fun sessionsAreEncryptedAndIsolatedByPortAndNotStoredInInstanceBackups() {
        check(context.packageName.endsWith(".debug"))
        val sessions = WorkshopSessionStore(context)
        val first = "https://workshop.example"
        val second = "https://workshop.example:8443"
        val response = JsonParser.parseString("""{"user":{"id":42,"name":"fixture"},"csrf_token":"fixturecsrf","expires_at":"2099-01-01T00:00:00Z"}""").asJsonObject
        try {
            sessions.save(first, "pvzce_session=fixturesecret", response)
            sessions.save(second, "pvzce_session=othersecret", response)
            assertEquals("pvzce_session=fixturesecret", sessions.read(first)?.cookie)
            assertEquals("pvzce_session=othersecret", sessions.read(second)?.cookie)
            val file = File(context.noBackupFilesDir, "pvzce_sessions/${WorkshopApi.key(first)}")
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("fixturesecret"))
            sessions.clear(first)
            assertNull(sessions.read(first))
            assertNotNull(sessions.read(second))
            assertFalse(File(context.filesDir, "pvzce_sessions").exists())
        } finally { sessions.clear(first); sessions.clear(second) }
    }
    @Test fun creatorChooserKeepsOriginalUploadTargetAcrossRecreation() {
        check(context.packageName.endsWith(".debug"))
        context.getSharedPreferences("pvzce_workshop", 0).edit().clear().commit()
        ActivityScenario.launch<WorkshopCreatorActivity>(Intent(context, WorkshopCreatorActivity::class.java)).use { scenario ->
            val choice = WorkshopCreatorViewModel.Selection("https://workshop.example", 42, 7, 9, "package", "mod")
            var retained: WorkshopCreatorViewModel? = null
            scenario.onActivity { activity ->
                retained = ViewModelProvider(activity)[WorkshopCreatorViewModel::class.java]
                retained!!.choosing = choice
                activity.findViewById<EditText>(R.id.pvzce_creator_title).setText("重建后保留的草稿")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[WorkshopCreatorViewModel::class.java]
                assertSame(retained, model)
                assertEquals(choice, model.choosing)
                assertEquals("重建后保留的草稿", activity.findViewById<EditText>(R.id.pvzce_creator_title).text.toString())
                assertNotNull(activity.findViewById<android.view.View>(R.id.pvzce_creator_upload))
                assertNull(activity.findViewById<android.view.View>(R.id.account))
            }
        }
    }

}
