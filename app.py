import gradio as gr
import requests
import json
import threading
import queue
from sseclient import SSEClient
import os

# --- Configuration ---
DEFAULT_MCP_STREAM_URL = os.getenv("MCP_STREAM_URL", "http://localhost:5001/mcp_stream")
DEFAULT_MCP_SEND_URL = os.getenv("MCP_SEND_URL", "http://localhost:5001/send_to_mcp")
DEFAULT_SIMPLE_LLM_URL = os.getenv("SIMPLE_LLM_URL", "http://localhost:5000/chat") # From earlier step

# --- Global State ---
sse_client = None
sse_listener_thread = None
message_queue = queue.Queue()

# --- SSE Connection and Listener (largely unchanged) ---
def _sse_listener_worker(client, q):
    try:
        print(f"SSE Worker: Starting to listen to client: {client}")
        for event in client:
            if event.event == "message" and event.data:
                try:
                    data = json.loads(event.data)
                    q.put(data)
                except json.JSONDecodeError:
                    q.put({"type": "error", "content": "Received malformed JSON data from server."})
                except Exception as e:
                    q.put({"type": "error", "content": f"SSE listener data processing error: {str(e)}"})
            elif event.event == "end":
                 q.put({"type": "end_of_stream", "content": "Server signaled end of response."})
        print("SSE Worker: Event loop finished.")
    except requests.exceptions.ChunkedEncodingError as e:
        q.put({"type": "error", "content": "Stream connection error (chunked encoding)."})
    except Exception as e:
        q.put({"type": "error", "content": f"Unhandled SSE listener error: {str(e)}"})
    finally:
        if client: client.close()
        q.put({"type": "stream_closed"})

def connect_and_listen_sse(mcp_stream_url_from_textbox):
    global sse_client, sse_listener_thread, message_queue
    if not mcp_stream_url_from_textbox:
        return "Error: MCP Stream URL cannot be empty."
    if sse_listener_thread and sse_listener_thread.is_alive():
        return "Already connected. Disconnect not implemented; restart app to change URL." # Simplified for now
    try:
        while not message_queue.empty():
            try: message_queue.get_nowait()
            except queue.Empty: break
        sse_client = SSEClient(mcp_stream_url_from_textbox, retry=3000)
        sse_listener_thread = threading.Thread(target=_sse_listener_worker, args=(sse_client, message_queue), daemon=True)
        sse_listener_thread.start()
        return f"Successfully connected to SSE stream at {mcp_stream_url_from_textbox}. Listening..."
    except Exception as e:
        sse_client = None
        return f"Failed to connect to SSE: {str(e)}"

# --- Chat Logic for different modes ---

def _stream_sse_responses(user_input_value, history_list, status_output_component):
    """
    Handles streaming of SSE responses from the message_queue.
    This is a generator.
    """
    global sse_client, sse_listener_thread # To allow resetting them if stream closes
    bot_response_accumulator = ""
    stream_ended_properly = False
    try:
        while True:
            try:
                event_data = message_queue.get(timeout=1.5) # Slightly longer timeout
                event_type = event_data.get("type")
                event_content = event_data.get("content", "")

                if event_type == "token":
                    bot_response_accumulator += event_content
                    history_list[-1][1] = bot_response_accumulator
                    yield history_list, user_input_value, f"Status: Streaming token... {len(bot_response_accumulator)} chars"
                elif event_type == "full_message":
                    history_list[-1][1] = event_content
                    yield history_list, user_input_value, "Status: Received full message."
                elif event_type == "end_of_stream":
                    stream_ended_properly = True
                    yield history_list, user_input_value, "Status: SSE Stream ended by server."
                    break
                elif event_type == "error":
                    history_list[-1][1] = (bot_response_accumulator + f"\n[Stream Error: {event_content}]").strip()
                    yield history_list, user_input_value, f"Status: SSE Stream Error: {event_content}"
                    break
                elif event_type == "stream_closed":
                    history_list[-1][1] = (bot_response_accumulator + "\n[Stream unexpectedly closed]").strip()
                    if sse_client: sse_client.close(); sse_client = None
                    if sse_listener_thread: sse_listener_thread = None
                    yield history_list, user_input_value, "Status: SSE Connection closed. Please reconnect."
                    break
            except queue.Empty:
                # No message for 1.5s. Consider this a timeout for current stream for this query.
                # Or, just continue if server is slow. For now, let's assume server should be responsive.
                # If we want to be more lenient, increase timeout or remove this explicit break.
                if bot_response_accumulator: # if we received something, assume it might just be a pause
                    yield history_list, user_input_value, "Status: Waiting for more tokens (queue empty for 1.5s)..."
                    continue # continue waiting
                else: # if nothing received for a while, maybe server didn't pick up
                    history_list[-1][1] = (bot_response_accumulator + "\n[Timeout waiting for SSE response start]").strip()
                    yield history_list, user_input_value, "Status: Timeout waiting for initial SSE response."
                    break
    except Exception as e:
        history_list[-1][1] = (bot_response_accumulator + f"\n[Client-side Error during stream: {str(e)}]").strip()
        yield history_list, user_input_value, f"Status: Client error during stream: {str(e)}"
    finally:
        if not history_list[-1][1] and not stream_ended_properly:
             history_list[-1][1] = "[No response or stream incomplete]"
        # Final yield to clear textbox is handled by the caller handle_chat_submit
        # yield history_list, "" # This would clear too early if handle_chat_submit has more yields

async def _handle_sse_chat_submission(user_input, history_list, mcp_send_url, status_output_component):
    """
    Handles the SSE mode chat submission.
    Posts to MCP, then yields updates from _stream_sse_responses.
    """
    global sse_client, sse_listener_thread

    if not sse_client or not sse_listener_thread or not sse_listener_thread.is_alive():
        error_msg = "Error: Not connected to SSE stream. Please connect first."
        history_list[-1][1] = error_msg
        yield history_list, "", f"Status: {error_msg}" # Update status, clear input
        return

    payload = {"prompt": user_input, "history": [item for item in history_list[:-1] if item[1] is not None]}
    try:
        # status_output_component.update(value="Status: Sending message to MCP...") # Direct update
        yield history_list, user_input, "Status: Sending message to MCP..." # Keep input, update status

        response = await gr.Request.post( # Use gr.Request for async POST if available/needed
            mcp_send_url, json=payload, timeout=20
        ) # If not, use requests.post and run in thread if truly async needed
        # For now, assume requests.post is fine if this function is run by Gradio in a thread
        # response = requests.post(mcp_send_url, json=payload, timeout=20)
        # response.raise_for_status() # This will be caught by RequestException

        # If using requests.post directly:
        # response = requests.post(mcp_send_url, json=payload, timeout=20)
        # response.raise_for_status()
        # For now, let's assume the Gradio default click behavior handles threading for blocking calls.
        # The example used requests.post before, implies it's okay.

        # Let's stick to requests for now as gr.Request.post is not standard
        response = requests.post(mcp_send_url, json=payload, timeout=20)
        response.raise_for_status()

        yield history_list, user_input, "Status: Message sent to MCP. Waiting for stream..."
    except requests.exceptions.RequestException as e:
        history_list[-1][1] = f"Error sending to MCP: {str(e)}"
        yield history_list, "", f"Status: Error sending to MCP: {str(e)}" # Clear input
        return

    # Stream responses
    async for hist, _, status_update_val in _stream_sse_responses(user_input, history_list, status_output_component):
        yield hist, user_input, status_update_val # Keep input during streaming

    # Final state after streaming finishes (or if it had error and broke)
    yield history_list, "", status_output_component.value # Clear input, keep last status

async def _handle_simple_post_chat_submission(user_input, history_list, simple_llm_url, status_output_component):
    """
    Handles the SimplePOST mode chat submission.
    Yields history for chatbot and clears textbox.
    """
    payload = {"prompt": user_input, "history": [item for item in history_list[:-1] if item[1] is not None]}
    try:
        # status_output_component.update(value="Status: Sending message via SimplePOST...") # Direct update
        yield history_list, user_input, "Status: Sending message via SimplePOST..." # Keep input, update status

        # response = await gr.Request.post(simple_llm_url, json=payload, timeout=30) # if using gr.Request
        response = requests.post(simple_llm_url, json=payload, timeout=30)
        response.raise_for_status()

        llm_response_text = response.json().get("response", "No valid 'response' key in JSON.")
        history_list[-1][1] = llm_response_text
        yield history_list, "", "Status: SimplePOST successful." # Clear input, update status
    except requests.exceptions.RequestException as e:
        history_list[-1][1] = f"Error (SimplePOST): {str(e)}"
        yield history_list, "", f"Status: SimplePOST Error: {str(e)}" # Clear input
    except json.JSONDecodeError:
        history_list[-1][1] = "Error: Invalid JSON response from SimplePOST server."
        yield history_list, "", "Status: SimplePOST JSON Decode Error."
    except Exception as e:
        history_list[-1][1] = f"Unexpected Error (SimplePOST): {str(e)}"
        yield history_list, "", f"Status: SimplePOST Unexpected Error: {str(e)}"


# --- Main Chat Handler ---
async def handle_chat_submit(user_input, history_list_from_state, current_mode,
                       simple_llm_url_val, mcp_send_url_val,
                       # These are for direct update, not yield. Consider if status should be yielded.
                       # For now, status_indicator is an output of the click event for handle_chat_submit
                       status_indicator_ref_val_not_used):
    """
    Main chat submission handler. Dispatches based on mode.
    This is a generator.
    """
    if not user_input.strip():
        yield history_list_from_state, "", "Status: Input cannot be empty."
        return

    history_list_from_state.append([user_input, None]) # Append user message, bot response is None initially
    # Initial yield to show user message immediately
    yield history_list_from_state, user_input, "Status: Processing..."


    if current_mode == "SSE":
        # The _handle_sse_chat_submission itself is an async generator
        async for hist, _, status_update_val in _handle_sse_chat_submission(user_input, history_list_from_state, mcp_send_url_val, status_indicator_ref_val_not_used):
            yield hist, user_input, status_update_val # SSE handler keeps input during stream
        # Final yield after SSE stream concludes (or errors out)
        yield history_list_from_state, "", status_indicator_ref_val_not_used.value if hasattr(status_indicator_ref_val_not_used, 'value') else "Status: SSE interaction complete."

    elif current_mode == "SimplePOST":
        # _handle_simple_post_chat_submission is also an async generator
        async for hist, _, status_update_val in _handle_simple_post_chat_submission(user_input, history_list_from_state, simple_llm_url_val, status_indicator_ref_val_not_used):
            yield hist, user_input, status_update_val # SimplePOST might also keep input until final response
        # Final yield after SimplePOST concludes
        yield history_list_from_state, "", status_indicator_ref_val_not_used.value if hasattr(status_indicator_ref_val_not_used, 'value') else "Status: SimplePOST interaction complete."
    else:
        history_list_from_state[-1][1] = "Error: Unknown mode selected."
        yield history_list_from_state, "", "Status: Unknown mode."


# --- UI Update Logic ---
def update_ui_for_mode(selected_mode):
    is_sse_mode = (selected_mode == "SSE")
    status_message = "Mode changed to SSE. Configure SSE URLs and connect if needed." if is_sse_mode else "Mode changed to SimplePOST. Configure Simple LLM URL."
    return {
        simple_llm_url_textbox: gr.update(visible=not is_sse_mode),
        mcp_stream_url_textbox: gr.update(visible=is_sse_mode),
        mcp_send_url_textbox: gr.update(visible=is_sse_mode),
        connect_button: gr.update(visible=is_sse_mode),
        # status_indicator: gr.update(value=status_message) # This causes error if status_indicator is also output of chat submit
    }

# --- Gradio UI Definition ---
with gr.Blocks(title="Unified Chatbot Interface") as demo:
    gr.Markdown("# Unified Chatbot (SSE & SimplePOST)")

    chat_history_state = gr.State([])

    mode_selector = gr.Radio(["SSE", "SimplePOST"], label="Operation Mode", value="SSE")

    status_indicator = gr.Textbox(label="Status", interactive=False, value="App started. Select mode and configure.", scale=3)

    with gr.Accordion("Connection Configuration", open=True):
        simple_llm_url_textbox = gr.Textbox(label="Simple POST LLM URL", value=DEFAULT_SIMPLE_LLM_URL, visible=(mode_selector.value == "SimplePOST"))
        mcp_stream_url_textbox = gr.Textbox(label="MCP Stream URL", value=DEFAULT_MCP_STREAM_URL, visible=(mode_selector.value == "SSE"))
        mcp_send_url_textbox = gr.Textbox(label="MCP Send URL", value=DEFAULT_MCP_SEND_URL, visible=(mode_selector.value == "SSE"))
        connect_button = gr.Button("Connect/Reconnect SSE Stream", visible=(mode_selector.value == "SSE"))

    chatbot_display = gr.Chatbot(label="Chatbot", bubble_full_width=False, height=500)

    with gr.Row():
        msg_textbox = gr.Textbox(label="Your Message", placeholder="Type message...", container=False, scale=7)
        send_button = gr.Button("Send", variant="primary", scale=1)

    # --- Event Wiring ---
    mode_selector.change(
        fn=update_ui_for_mode,
        inputs=mode_selector,
        outputs=[simple_llm_url_textbox, mcp_stream_url_textbox, mcp_send_url_textbox, connect_button] # Removed status_indicator from here
    )
    # You might need a separate .change to update status_indicator or do it via another mechanism if it conflicts.
    # For now, status_indicator is primarily updated by chat submissions.

    connect_button.click(
        fn=connect_and_listen_sse,
        inputs=[mcp_stream_url_textbox],
        outputs=[status_indicator]
    )

    chat_submit_inputs = [
        msg_textbox, chat_history_state, mode_selector,
        simple_llm_url_textbox, mcp_send_url_textbox, status_indicator
    ]
    chat_submit_outputs = [chatbot_display, msg_textbox, status_indicator]

    send_button.click(
        fn=handle_chat_submit,
        inputs=chat_submit_inputs,
        outputs=chat_submit_outputs
    )
    msg_textbox.submit(
        fn=handle_chat_submit,
        inputs=chat_submit_inputs,
        outputs=chat_submit_outputs
    )

    def cleanup_on_unload():
        global sse_client, sse_listener_thread
        if sse_client: sse_client.close(); sse_client = None
        if sse_listener_thread and sse_listener_thread.is_alive():
            sse_listener_thread.join(timeout=1)
        sse_listener_thread = None
    demo.unload(cleanup_on_unload, inputs=None, outputs=None)

# Main execution block
if __name__ == "__main__":
    # The gr.Blocks() definition and all UI elements up to demo.launch()
    # are effectively part of the "UI Definition" and should be here.
    # However, to make functions importable, the UI components they reference
    # (like simple_llm_url_textbox, mcp_stream_url_textbox etc.)
    # must be defined at a scope accessible to them if they are not passed as arguments.
    # For `update_ui_for_mode`, these are global.
    # For `handle_chat_submit`, they are passed as inputs.

    # Re-check: `simple_llm_url_textbox` etc. are defined inside `with gr.Blocks() as demo:`.
    # `update_ui_for_mode` accesses them. This means `demo` and its contents
    # might need to be globally accessible or `update_ui_for_mode` needs to take them as gr.Textbox inputs.
    # The current structure of `update_ui_for_mode` returning a dict of updates implies it works
    # by component reference, which means those components must be in scope.
    # Let's assume the current structure where these are defined at the top level of the `with demo:` block works for imports.

    demo.queue()
    demo.launch(debug=True, share=False)
