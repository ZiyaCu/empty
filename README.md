# Gradio Chat Interface with Remote LLM and MCP/SSE

## Overview

This application provides a Gradio-based chat interface that supports two distinct modes for interacting with Large Language Models (LLMs):
1.  **Simple POST Mode:** Sends user prompts to a remote LLM endpoint via a standard HTTP POST request and displays the complete response.
2.  **MCP/SSE Mode:** Interacts with a Model Context Protocol (MCP) compliant server using Server-Sent Events (SSE) for streaming responses token by token, providing a more interactive experience.

The application allows users to switch between these modes and configure the necessary API endpoints through the user interface.

## Features

*   Interactive chat interface built with Gradio.
*   **Simple POST Mode:** For direct, non-streaming interaction with an LLM.
*   **MCP/SSE Mode:** For streaming responses from an MCP-compatible server using Server-Sent Events.
*   Configurable URLs for both the Simple POST LLM endpoint and the MCP server (stream and send URLs).
*   Dynamic UI that adjusts input fields based on the selected operation mode.
*   Status indicators for SSE connection and message sending.
*   Basic error handling for network requests and SSE stream events.
*   Comprehensive unit test suite.

## Prerequisites

*   Python 3.8+
*   An active internet connection.
*   **For Simple POST Mode:** Access to a remote LLM endpoint that accepts JSON payloads with `{"prompt": "your message", "history": [...]}` and returns a JSON response like `{"response": "llm answer"}`.
*   **For MCP/SSE Mode:** Access to an MCP-compatible server that provides:
    *   An SSE streaming endpoint (e.g., for token-by-token responses).
    *   A separate HTTP endpoint to send user messages/prompts to, which then triggers events on the SSE stream.

## Setup & Installation

1.  **Clone the repository:**
    ```bash
    git clone <repository_url>
    ```
    (Replace `<repository_url>` with the actual URL of this repository)

2.  **Navigate to the project directory:**
    ```bash
    cd <repository_name>
    ```
    (Replace `<repository_name>` with the name of the cloned directory)

3.  **Install dependencies:**
    ```bash
    pip install -r requirements.txt
    ```

## Configuration

The application allows you to configure the necessary URLs for the LLM and MCP services directly within the user interface:

*   **Simple POST LLM URL:** The endpoint for the direct LLM interaction in "SimplePOST" mode.
*   **MCP Stream URL:** The SSE endpoint for receiving streamed responses in "SSE" mode.
*   **MCP Send URL:** The HTTP endpoint to which user messages are sent to trigger the MCP process in "SSE" mode.

Default URLs are provided in `app.py` (e.g., `DEFAULT_SIMPLE_LLM_URL`, `DEFAULT_MCP_SEND_URL`, `DEFAULT_MCP_STREAM_URL`). These can be modified directly in the `app.py` file for persistent changes, or overridden by setting the corresponding environment variables (`SIMPLE_LLM_URL`, `MCP_STREAM_URL`, `MCP_SEND_URL`) before launching the application. However, the UI provides text boxes to change these URLs dynamically during runtime.

## Running the Application

1.  Execute the main application script:
    ```bash
    python app.py
    ```
2.  Open your web browser and navigate to the local URL displayed in your terminal (typically `http://127.0.0.1:7860`).

## How to Use

Upon launching, you will see the Gradio interface.

1.  **Select Operation Mode:**
    *   Use the "Operation Mode" radio buttons at the top to choose between "SSE" and "SimplePOST".
    *   The UI will dynamically show/hide relevant URL configuration fields based on your selection.

2.  **SimplePOST Mode:**
    *   Select the "SimplePOST" mode.
    *   The "Simple POST LLM URL" textbox will become visible. Ensure it's set to your desired LLM endpoint.
    *   Type your message in the input box and press Enter or click "Send".
    *   The LLM's full response will appear in the chat history.

3.  **SSE Mode:**
    *   Select the "SSE" mode.
    *   The "MCP Stream URL" and "MCP Send URL" textboxes will become visible. Ensure these are correctly set for your MCP server.
    *   Click the **"Connect/Reconnect SSE Stream"** button.
    *   Observe the "Connection Status" indicator. It should update to show a successful connection. If it fails, check your MCP Stream URL and server status.
    *   Once connected, type your message in the input box and press Enter or click "Send".
        *   This sends your message to the "MCP Send URL".
        *   The MCP server should then start sending events over the established SSE connection ("MCP Stream URL").
    *   The bot's response should stream into the chat area token by token.
    *   The "Status" indicator will provide feedback on the message sending and streaming process.

## Running Tests

To run the automated unit tests for this application:

1.  Ensure you are in the root directory of the project.
2.  Run the following command in your terminal:
    ```bash
    python -m unittest discover tests
    ```
    This will automatically find and run all tests located in the `tests` directory.

## Project Structure

```
.
├── app.py              # Main Gradio application script
├── requirements.txt    # Python dependencies
├── tests/              # Unit tests
│   ├── __init__.py     # Makes 'tests' a Python package
│   └── test_app.py   # Tests for app.py functions
└── README.md           # This file
```
