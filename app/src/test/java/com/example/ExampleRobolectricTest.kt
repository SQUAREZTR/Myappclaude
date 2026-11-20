package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.nvidia.NvidiaNimClient
import com.example.engine.python.PythonInterpreter
import com.example.engine.shell.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `read app name from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("NimTerm", appName)
  }

  @Test
  fun `python interpreter executes functions classes file io and stdlib`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val workspace = WorkspaceManager(context)
    var cwd = workspace.rootDir
    val stdout = StringBuilder()
    val stderr = StringBuilder()

    val py = PythonInterpreter(
      workspace = workspace,
      cwdProvider = { cwd },
      cwdSetter = { cwd = it },
      nimClient = NvidiaNimClient(),
      apiKeyProvider = { "" },
      baseUrlProvider = { NvidiaNimClient.BASE_URL },
      activeModelProvider = { "meta/llama-3.3-70b-instruct" },
      onStdout = { stdout.append(it) },
      onStderr = { stderr.append(it) },
      onInput = { "test" }
    )

    val script = """
      import json, math
      
      class Counter:
          def __init__(self, start=0):
              self.val = start
          def inc(self, step=1):
              self.val += step
              return self.val
      
      c = Counter(10)
      c.inc(5)
      squares = [x * x for x in range(1, 5) if x % 2 == 0]
      with open("out_test.json", "w") as f:
          f.write(json.dumps({"val": c.val, "squares": squares}))
      with open("out_test.json", "r") as f:
          loaded = json.loads(f.read())
      print(f"val={loaded['val']} sq={loaded['squares']} root={round(math.sqrt(16), 1)}")
    """.trimIndent()

    val res = py.execute(script, "test.py")
    assertTrue("Expected success, stderr=$stderr", res.isSuccess)
    assertEquals("", stderr.toString())
    assertTrue("Unexpected stdout: $stdout", stdout.toString().contains("val=15 sq=[4, 16] root=4.0"))
  }

  @Test
  fun `terminal ui executes claude code slash commands skills and python passthrough`() {
    composeRule.onNodeWithTag("terminal_command_input").assertIsDisplayed()

    // 1. Starts directly in Claude Code CLI mode with welcome banner
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Claude Code CLI", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 2. Typing `/` opens the interactive slash command menu
    composeRule.onNodeWithTag("terminal_command_input").performTextInput("/")
    composeRule.onNodeWithTag("slash_command_menu").assertIsDisplayed()

    // 3. Complete `/skills` and run it
    composeRule.onNodeWithTag("terminal_command_input").performTextInput("skills")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("python-dev", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 4. Load a skill via `/skill python-dev`
    composeRule.onNodeWithTag("terminal_command_input").performTextInput("/skill python-dev")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Loaded skill", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 5. Run bash & python passthrough commands via `!`
    composeRule.onNodeWithTag("terminal_command_input").performTextInput("!ls")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("hello.py", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("terminal_command_input").performTextInput("!python3 hello.py")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Hello Android!", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 6. Create & run inside an isolated workspace via `/workspace new lab-app`
    composeRule.onNodeWithTag("terminal_command_input").performTextInput("/workspace new lab-app")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Created & switched to isolated workspace", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("terminal_command_input").performTextInput("!python3 main.py")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Workspace 'lab-app' ready!", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 7. Paginated `/models 2` and `/modelno 8` command in terminal
    composeRule.onNodeWithTag("terminal_command_input").performTextInput("/models 2")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Models Page 2/", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("terminal_command_input").performTextInput("/modelno 8")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Switched active model (#8)", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 8. Exit to standard Shell mode via `/exit`
    composeRule.onNodeWithTag("terminal_command_input").performTextInput("/exit")
    composeRule.onNodeWithTag("terminal_send_button").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Switched to Workspace Shell", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    // 9. Switch tabs to Workspace (verify workspace chip) and Models (verify pagination)
    composeRule.onNodeWithTag("tab_workspace").performClick()
    composeRule.onNodeWithTag("workspace_chip_lab-app").assertIsDisplayed()

    composeRule.onNodeWithTag("tab_models").performClick()
    composeRule.onNodeWithTag("models_pagination_bar").assertIsDisplayed()
    composeRule.onNodeWithTag("models_next_page").performClick()
    composeRule.waitUntil(5000) {
      composeRule.onAllNodesWithText("Page 2/", substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("tab_settings").performClick()
    composeRule.onNodeWithTag("api_key_input").assertIsDisplayed()
  }
}
