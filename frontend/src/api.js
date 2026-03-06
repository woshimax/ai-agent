const BASE = '/api';

export async function registerUser(username, password) {
  const res = await fetch(
    `${BASE}/user/register?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`,
    { method: 'POST' }
  );
  return res.json();
}

export async function loginUser(username, password) {
  const res = await fetch(
    `${BASE}/user/login?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`,
    { method: 'POST' }
  );
  return res.json();
}

export async function getUser(id) {
  const res = await fetch(`${BASE}/user/get?id=${encodeURIComponent(id)}`);
  return res.json();
}

export async function createChat(userId) {
  const res = await fetch(`${BASE}/user/chat/create?userId=${encodeURIComponent(userId)}`, {
    method: 'POST',
  });
  return res.json();
}

export async function listChats(userId) {
  const res = await fetch(`${BASE}/user/chat/list?userId=${encodeURIComponent(userId)}`);
  return res.json();
}

export async function getChatHistory(chatId) {
  const res = await fetch(`${BASE}/emotion/history?chatId=${encodeURIComponent(chatId)}`);
  return res.json();
}

export async function generateTitle(message, chatId) {
  const res = await fetch(
    `${BASE}/emotion/title?message=${encodeURIComponent(message)}&chatId=${encodeURIComponent(chatId)}`,
    { method: 'POST' }
  );
  return res.json();
}

export async function getEmotionReport(chatId) {
  const res = await fetch(
    `${BASE}/emotion/report?chatId=${encodeURIComponent(chatId)}`
  );
  return res.json();
}

/**
 * SSE streaming chat — returns an AbortController so the caller can cancel.
 * onChunk(text)  is called for every chunk of text received.
 * onDone()       is called when the stream finishes.
 * onError(err)   is called on failure.
 */
export function streamChat(message, chatId, { onChunk, onDone, onError }) {
  const controller = new AbortController();
  const url = `${BASE}/emotion/chat?message=${encodeURIComponent(message)}&chatId=${encodeURIComponent(chatId)}`;

  fetch(url, { signal: controller.signal })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // Parse SSE: each event is "data:xxx\n"
        // Keep the last segment in buffer — it may be an incomplete line
        const parts = buffer.split('\n');
        buffer = parts.pop();
        for (const line of parts) {
          if (line.startsWith('data:')) {
            const payload = line.slice(5);
            if (payload.length > 0) {
              onChunk(payload);
            }
          }
        }
      }
      onDone?.();
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        onError?.(err);
      }
    });

  return controller;
}
