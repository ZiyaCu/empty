import unittest
from unittest.mock import patch, MagicMock, call, AsyncMock
import queue
import sys
import os
import asyncio

# Adjust sys.path to allow importing from the parent directory (where app.py is)
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

# Conditional import of Gradio for type hinting or if needed for mocks
# We try to avoid importing gradio itself if it's not strictly necessary for unit tests
# as it can be heavy. We will mock gr.update directly.
# import gradio as gr

# Import functions and variables from app.py
# We need to ensure that app.py can be imported without running demo.launch()
# and that UI components are defined in a way that their names can be used as keys
# in the dictionary returned by update_ui_for_mode.
from app import (
    _handle_simple_post_chat_submission,
    _sse_listener_worker,
    connect_and_listen_sse,
    _handle_sse_chat_submission, # Renamed from send_message_to_mcp's core
    _stream_sse_responses,      # Extracted from previous send_message_to_mcp
    update_ui_for_mode,
    handle_chat_submit,
    # UI component names that update_ui_for_mode uses as keys.
    # These won't be actual Gradio components in the test environment,
    # but we need their names for dictionary keys if testing update_ui_for_mode's return value structure.
    # For testing update_ui_for_mode, we will mock gr.update and check its calls.
    # So, we don't strictly need to import the component variables themselves if they are not globally accessible.
    # However, if update_ui_for_mode refers to them as free variables, Python will try to resolve them.
    # Let's assume for now that the test for update_ui_for_mode will mock these names if needed,
    # or that the test focuses on the gr.update calls rather than the dict keys if they are objects.
)

# Mock global variables from app.py that are accessed by some functions
# We might need to patch 'app.message_queue', 'app.sse_client', 'app.sse_listener_thread'
# For components used as keys in update_ui_for_mode, we'll create mock objects
# if the function expects actual component instances rather than just using their names.
# The function `update_ui_for_mode` returns a dictionary like:
# { component_instance_actual_ref: gr.update(...) }
# So for testing it, we need mock objects for these component "names" if they are indeed object references.

# Mock Gradio components that are dictionary keys in update_ui_for_mode
# These are usually defined within the `with gr.Blocks() as demo:` context in app.py
# For testing update_ui_for_mode in isolation, we define them here as mock objects
# so that the function can be called.
simple_llm_url_textbox = MagicMock(name="simple_llm_url_textbox")
mcp_stream_url_textbox = MagicMock(name="mcp_stream_url_textbox")
mcp_send_url_textbox = MagicMock(name="mcp_send_url_textbox")
connect_button = MagicMock(name="connect_button")
status_indicator = MagicMock(name="status_indicator") # Though this one was removed as an output of update_ui_for_mode

# Helper to run async generator tests
def run_async_gen_test(async_gen):
    results = []
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        while True:
            results.append(loop.run_until_complete(async_gen.__anext__()))
    except StopAsyncIteration:
        pass
    finally:
        loop.close()
    return results

class TestSimplePostMode(unittest.TestCase):

    @patch('app.requests.post')
    def test_simple_post_success(self, mock_post):
        # Mock the response from requests.post
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"response": "Test bot response"}
        mock_post.return_value = mock_response

        history = []
        user_input = "Hello"
        llm_url = "http://fake.url/chat"
        mock_status_output_component = MagicMock() # This isn't used by _handle_simple_post_chat_submission directly for updates

        # The function is an async generator
        gen = _handle_simple_post_chat_submission(user_input, history, llm_url, mock_status_output_component)

        results = run_async_gen_test(gen)

        # Expected yields:
        # 1. User message shown: (history_with_user_msg, user_input, "Status: Sending message via SimplePOST...")
        # 2. Bot response shown: (history_with_bot_msg, "", "Status: SimplePOST successful.")

        self.assertEqual(len(results), 2)

        # Check first yield
        hist1, msg_val1, status1 = results[0]
        self.assertEqual(hist1, [["Hello", None]])
        self.assertEqual(msg_val1, "Hello")
        self.assertEqual(status1, "Status: Sending message via SimplePOST...")

        # Check second yield
        hist2, msg_val2, status2 = results[1]
        self.assertEqual(hist2, [["Hello", "Test bot response"]])
        self.assertEqual(msg_val2, "") # Input cleared
        self.assertEqual(status2, "Status: SimplePOST successful.")

        mock_post.assert_called_once_with(
            llm_url,
            json={"prompt": "Hello", "history": []},
            timeout=30
        )

    @patch('app.requests.post')
    def test_simple_post_request_exception(self, mock_post):
        # Mock requests.post to raise an exception
        mock_post.side_effect = requests.exceptions.RequestException("Connection error")

        history = []
        user_input = "Hello again"
        llm_url = "http://fake.url/chat"
        mock_status_output_component = MagicMock()

        gen = _handle_simple_post_chat_submission(user_input, history, llm_url, mock_status_output_component)
        results = run_async_gen_test(gen)

        # Expected yields:
        # 1. User message shown: (history_with_user_msg, user_input, "Status: Sending message via SimplePOST...")
        # 2. Error message shown: (history_with_error_msg, "", "Status: SimplePOST Error: Connection error")

        self.assertEqual(len(results), 2)

        # Check first yield
        hist1, msg_val1, status1 = results[0]
        self.assertEqual(hist1, [["Hello again", None]])
        self.assertEqual(msg_val1, "Hello again")

        # Check second yield (error case)
        hist2, msg_val2, status2 = results[1]
        self.assertEqual(hist2, [["Hello again", "Error (SimplePOST): Connection error"]])
        self.assertEqual(msg_val2, "") # Input cleared
        self.assertEqual(status2, "Status: SimplePOST Error: Connection error")

class TestSSEConnection(unittest.TestCase):
    @patch('app.threading.Thread')
    @patch('app.SSEClient') # Patching SSEClient constructor
    def test_connect_sse_success(self, MockSSEClient, MockThread):
        mock_sse_client_instance = MagicMock()
        MockSSEClient.return_value = mock_sse_client_instance

        mock_thread_instance = MagicMock()
        MockThread.return_value = mock_thread_instance

        # Reset global message_queue before test
        app_message_queue = app.message_queue
        while not app_message_queue.empty():
            try: app_message_queue.get_nowait()
            except queue.Empty: break

        result_status = connect_and_listen_sse("http://fake.sse/stream")

        self.assertTrue("Successfully connected" in result_status)
        MockSSEClient.assert_called_once_with("http://fake.sse/stream", retry=3000)
        MockThread.assert_called_once() # Check a thread was created
        # Check if target of thread is _sse_listener_worker and args are correct
        args, kwargs = MockThread.call_args
        self.assertEqual(args[0], app._sse_listener_worker)
        self.assertEqual(args[1], mock_sse_client_instance) # Arg 1 is the client
        self.assertEqual(args[2], app_message_queue)      # Arg 2 is the queue
        mock_thread_instance.start.assert_called_once()


    @patch('app.SSEClient') # Patching SSEClient constructor
    def test_connect_sse_failure(self, MockSSEClient):
        MockSSEClient.side_effect = Exception("Connection failed badly")

        result_status = connect_and_listen_sse("http://bad.sse/stream")

        self.assertTrue("Failed to connect to SSE: Connection failed badly" in result_status)
        self.assertIsNone(app.sse_client) # Ensure sse_client global is reset


# Placeholder for more tests - to be completed in subsequent steps if this gets too large
class TestSSEListenerWorker(unittest.TestCase):
    def test_sse_event_processing(self):
        mock_sse_client = MagicMock()
        # Simulate different types of events
        mock_events = [
            MagicMock(event="message", data=json.dumps({"type": "token", "content": "Hello"})),
            MagicMock(event="message", data=json.dumps({"type": "full_message", "content": "World"})),
            MagicMock(event="end", data=None), # SSEClient yields event objects, data might be empty for some types
            MagicMock(event="message", data="invalid_json_data"), # Malformed JSON
        ]
        # Make the mock client iterable and return our mock events
        mock_sse_client.__iter__.return_value = iter(mock_events)

        q = queue.Queue()
        _sse_listener_worker(mock_sse_client, q)

        # Check queue contents
        self.assertEqual(q.get_nowait(), {"type": "token", "content": "Hello"})
        self.assertEqual(q.get_nowait(), {"type": "full_message", "content": "World"})
        self.assertEqual(q.get_nowait(), {"type": "end_of_stream", "content": "Server signaled end of response."})
        error_event = q.get_nowait()
        self.assertEqual(error_event["type"], "error")
        self.assertTrue("Received malformed JSON data" in error_event["content"])

        # After events are processed, stream_closed should be put
        self.assertEqual(q.get_nowait(), {"type": "stream_closed"})
        self.assertTrue(q.empty())
        mock_sse_client.close.assert_called_once()


class TestHandleChatSubmit(unittest.TestCase):
    # This will test the main dispatching logic of handle_chat_submit
    # It will need to mock the sub-handlers like _handle_simple_post_chat_submission
    # and _handle_sse_chat_submission.

    @patch('app._handle_simple_post_chat_submission', new_callable=AsyncMock) # Mock with AsyncMock for async generator
    def test_dispatch_to_simple_post(self, mock_simple_post_handler):
        # Setup mock_simple_post_handler to behave like an async generator
        async def simple_post_gen_mock(*args, **kwargs):
            yield ([["user input", "bot response"]], ""), "Status: SimplePOST from mock"
        mock_simple_post_handler.side_effect = simple_post_gen_mock

        history = []
        status_mock = MagicMock() # Represents the status indicator component/value

        # Call handle_chat_submit in SimplePOST mode
        gen = handle_chat_submit(
            user_input="test simple",
            history_list_from_state=history,
            current_mode="SimplePOST",
            simple_llm_url_val="http://simple.url",
            mcp_send_url_val="http://mcp.url",
            status_indicator_ref_val_not_used=status_mock
        )
        results = run_async_gen_test(gen)

        # First yield from handle_chat_submit itself (user message added)
        # Then yields from the mocked sub-handler
        self.assertTrue(len(results) > 1)
        self.assertEqual(results[0][0], [["test simple", None]]) # User message added
        self.assertEqual(results[0][2], "Status: Processing...")

        # Check that the mocked handler was called
        mock_simple_post_handler.assert_called_once()
        # Check the final result from the mocked sub-handler is propagated
        final_hist, final_msg_txt, final_status = results[-1]
        self.assertEqual(final_hist, [["test simple", "bot response"]])
        self.assertEqual(final_msg_txt, "")

    @patch('app._handle_sse_chat_submission', new_callable=AsyncMock)
    def test_dispatch_to_sse(self, mock_sse_handler):
        async def sse_gen_mock(*args, **kwargs):
            # Simulate some SSE streaming yields
            yield ([["user sse", "token1"]], "user sse", "Status: SSE mock streaming")
            yield ([["user sse", "token1 final"]], "", "Status: SSE mock complete")
        mock_sse_handler.side_effect = sse_gen_mock

        history = []
        status_mock = MagicMock()
        status_mock.value = "Initial status" # for the final yield to pick up from status_indicator_ref_val_not_used.value

        gen = handle_chat_submit(
            user_input="test sse",
            history_list_from_state=history,
            current_mode="SSE",
            simple_llm_url_val="http://simple.url",
            mcp_send_url_val="http://mcp.url",
            status_indicator_ref_val_not_used=status_mock
        )
        results = run_async_gen_test(gen)

        self.assertTrue(len(results) > 1)
        self.assertEqual(results[0][0], [["test sse", None]]) # User message added
        self.assertEqual(results[0][2], "Status: Processing...")

        mock_sse_handler.assert_called_once()
        final_hist, final_msg_txt, final_status = results[-1]
        self.assertEqual(final_hist, [["test sse", "token1 final"]])
        self.assertEqual(final_msg_txt, "")
        self.assertEqual(final_status, "Initial status") # From status_mock.value

class TestSSEChatProcessing(unittest.TestCase):
    @patch('app.requests.post') # Mock the initial POST to MCP
    @patch('app.message_queue') # Mock the message queue
    def test_handle_sse_chat_submission_success_and_streaming(self, mock_message_queue, mock_mcp_post):
        # Configure mock_mcp_post for success
        mock_mcp_post_response = MagicMock()
        mock_mcp_post_response.status_code = 200
        mock_mcp_post.return_value = mock_mcp_post_response

        # Configure mock_message_queue to simulate SSE events
        sse_events_to_simulate = [
            {"type": "token", "content": "First part. "},
            {"type": "token", "content": "Second part."},
            {"type": "end_of_stream"}
        ]
        # Make queue.get side effect iterable
        mock_message_queue.get.side_effect = sse_events_to_simulate + [queue.Empty()] # Add Empty to stop after events

        history = [] # Initial chat history
        user_input = "Test SSE streaming"
        mcp_send_url = "http://fake-mcp-send.url"
        mock_status_comp = MagicMock() # Mock for status component if directly updated

        # _handle_sse_chat_submission is an async generator
        gen = _handle_sse_chat_submission(user_input, history, mcp_send_url, mock_status_comp)
        results = run_async_gen_test(gen)

        # Expected yields:
        # 1. User msg + Status sending: ([["Test SSE streaming", None]], "Test SSE streaming", "Status: Sending message to MCP...")
        # 2. Status waiting for stream: ([["Test SSE streaming", None]], "Test SSE streaming", "Status: Message sent to MCP. Waiting for stream...")
        # 3. Token 1: ([["Test SSE streaming", "First part. "]], "Test SSE streaming", "Status: Streaming token... 12 chars")
        # 4. Token 2: ([["Test SSE streaming", "First part. Second part."]], "Test SSE streaming", "Status: Streaming token... 26 chars")
        # 5. End of stream: ([["Test SSE streaming", "First part. Second part."]], "Test SSE streaming", "Status: SSE Stream ended by server.")
        # 6. Final (called by handle_chat_submit, not directly by _handle_sse_chat_submission):
        #    ([["Test SSE streaming", "First part. Second part."]], "", <last_status>)

        self.assertGreaterEqual(len(results), 5) # Check we have enough yields

        # Yield 1 (status sending)
        hist1, msg_txt1, status1 = results[0]
        self.assertEqual(hist1, [["Test SSE streaming", None]])
        self.assertEqual(msg_txt1, user_input)
        self.assertEqual(status1, "Status: Sending message to MCP...")

        # Yield 2 (status waiting)
        hist2, msg_txt2, status2 = results[1]
        self.assertEqual(hist2, [["Test SSE streaming", None]]) # History unchanged by this status update
        self.assertEqual(status2, "Status: Message sent to MCP. Waiting for stream...")

        # Yield 3 (token 1)
        hist3, _, status3 = results[2]
        self.assertEqual(hist3[-1][1], "First part. ")
        self.assertTrue("Streaming token" in status3)

        # Yield 4 (token 2)
        hist4, _, status4 = results[3]
        self.assertEqual(hist4[-1][1], "First part. Second part.")
        self.assertTrue("Streaming token" in status4)

        # Yield 5 (end of stream)
        hist5, _, status5 = results[4]
        self.assertEqual(hist5[-1][1], "First part. Second part.") # Content should be final
        self.assertEqual(status5, "Status: SSE Stream ended by server.")

        mock_mcp_post.assert_called_once_with(mcp_send_url, json={"prompt": user_input, "history": []}, timeout=20)
        # Check that message_queue.get was called multiple times
        self.assertGreaterEqual(mock_message_queue.get.call_count, len(sse_events_to_simulate))


    @patch('app.requests.post') # Mock the initial POST to MCP
    def test_handle_sse_chat_submission_post_fails(self, mock_mcp_post):
        mock_mcp_post.side_effect = requests.exceptions.RequestException("MCP unavailable")

        history = []
        user_input = "Test MCP POST failure"
        mcp_send_url = "http://fake-mcp-send.url"
        mock_status_comp = MagicMock()

        gen = _handle_sse_chat_submission(user_input, history, mcp_send_url, mock_status_comp)
        results = run_async_gen_test(gen)

        # Expected yields:
        # 1. User msg + Status sending: (history_with_user_msg, user_input, "Status: Sending message to MCP...")
        # 2. Error from POST: (history_with_error, "", "Status: Error sending to MCP: MCP unavailable")
        self.assertEqual(len(results), 2)

        hist1, msg_txt1, status1 = results[0]
        self.assertEqual(hist1, [[user_input, None]])
        self.assertEqual(msg_txt1, user_input)
        self.assertEqual(status1, "Status: Sending message to MCP...")

        hist2, msg_txt2, status2 = results[1]
        self.assertEqual(hist2, [[user_input, "Error sending to MCP: MCP unavailable"]])
        self.assertEqual(msg_txt2, "") # Input cleared
        self.assertEqual(status2, "Status: Error sending to MCP: MCP unavailable")


    # Further tests could be added for _stream_sse_responses directly,
    # especially for queue error events, stream_closed events, and timeout behaviors.
    # For example:
    @patch('app.message_queue')
    def test_stream_sse_responses_handles_error_event(self, mock_message_queue):
        error_event_from_queue = {"type": "error", "content": "Something bad happened in SSE stream"}
        mock_message_queue.get.side_effect = [error_event_from_queue, queue.Empty()]

        history = [["User says", None]] # Simulating user message already added
        user_input_val = "User says"
        mock_status_comp = MagicMock()

        gen = _stream_sse_responses(user_input_val, history, mock_status_comp)
        results = run_async_gen_test(gen)

        self.assertEqual(len(results), 1)
        hist, _, status = results[0]
        self.assertEqual(hist[-1][1], "\n[Stream Error: Something bad happened in SSE stream]")
        self.assertTrue("Status: SSE Stream Error" in status)


class TestUIVisibility(unittest.TestCase):
    @patch('gradio.update') # Patch gr.update
    def test_update_ui_for_sse_mode(self, mock_gr_update):
        # We need to ensure that the component variables used as keys
        # are resolvable. We defined them as MagicMock at the top of this file.
        # This means update_ui_for_mode needs to be imported *after* these mocks are defined,
        # or these mocks need to be part of the 'app' module if 'from app import *' behavior is assumed.
        # The current import `from app import update_ui_for_mode` should work with top-level mocks here.

        # Make gr.update return itself or a marker to check calls easily
        mock_gr_update.side_effect = lambda visible: {"visible": visible}


        # Define mock components here, as they are keys in the returned dict.
        # Their names must match what update_ui_for_mode expects.
        # These are defined globally in this test file for now.
        # If app.py defines them globally (even as None before UI init), tests can patch app.<component_name>
        # For now, test assumes update_ui_for_mode will use the globally defined mocks in this test file.

        # To properly test this, we need to ensure that update_ui_for_mode
        # can access these component names. Let's assume it's structured to do so,
        # or we inject them.
        # The dictionary returned is {component_object: update_spec}
        # So, the keys will be our MagicMock objects.

        # Re-assigning here for clarity inside the test, though they are global mocks
        mock_simple_llm_url_textbox = simple_llm_url_textbox
        mock_mcp_stream_url_textbox = mcp_stream_url_textbox
        mock_mcp_send_url_textbox = mcp_send_url_textbox
        mock_connect_button = connect_button

        app.simple_llm_url_textbox = mock_simple_llm_url_textbox
        app.mcp_stream_url_textbox = mock_mcp_stream_url_textbox
        app.mcp_send_url_textbox = mock_mcp_send_url_textbox
        app.connect_button = mock_connect_button


        updates = update_ui_for_mode("SSE")

        # Check the generated update dictionary
        self.assertEqual(updates[mock_simple_llm_url_textbox], {"visible": False})
        self.assertEqual(updates[mock_mcp_stream_url_textbox], {"visible": True})
        self.assertEqual(updates[mock_mcp_send_url_textbox], {"visible": True})
        self.assertEqual(updates[mock_connect_button], {"visible": True})


    @patch('gradio.update')
    def test_update_ui_for_simple_post_mode(self, mock_gr_update):
        mock_gr_update.side_effect = lambda visible: {"visible": visible}

        # Same component mocking as above
        mock_simple_llm_url_textbox = simple_llm_url_textbox
        mock_mcp_stream_url_textbox = mcp_stream_url_textbox
        mock_mcp_send_url_textbox = mcp_send_url_textbox
        mock_connect_button = connect_button

        app.simple_llm_url_textbox = mock_simple_llm_url_textbox
        app.mcp_stream_url_textbox = mock_mcp_stream_url_textbox
        app.mcp_send_url_textbox = mock_mcp_send_url_textbox
        app.connect_button = mock_connect_button

        updates = update_ui_for_mode("SimplePOST")

        self.assertEqual(updates[mock_simple_llm_url_textbox], {"visible": True})
        self.assertEqual(updates[mock_mcp_stream_url_textbox], {"visible": False})
        self.assertEqual(updates[mock_mcp_send_url_textbox], {"visible": False})
        self.assertEqual(updates[mock_connect_button], {"visible": False})


if __name__ == '__main__':
    # Important: If app.py defines Gradio components at the global level after import,
    # those definitions might interfere if not handled.
    # The `if __name__ == "__main__":` in app.py should prevent UI launch.
    # For `update_ui_for_mode` tests to work as written, the component names like
    # `simple_llm_url_textbox` must be resolvable. We are mocking them globally in this file.
    unittest.main()
