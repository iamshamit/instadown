// InstaDown PWA — main client logic.
// Handles: share-target incoming, paste-URL form, API call, render
// results, save file to phone via the standard <a download> flow.

(function () {
  "use strict";

  const $url = document.getElementById("url");
  const $go = document.getElementById("go");
  const $status = document.getElementById("status");
  const $results = document.getElementById("results");

  // 1) Receive share-target posts from the service worker.
  navigator.serviceWorker?.addEventListener("message", (ev) => {
    if (ev.data?.type === "share" && ev.data.url) {
      $url.value = ev.data.url;
      doDownload();
    }
  });

  // 2) Receive share-target redirects (when the SW opens us with ?shared=…).
  const shared = new URLSearchParams(location.search).get("shared");
  if (shared) {
    $url.value = shared;
    // wait for the SW to take control, then fire
    navigator.serviceWorker?.ready?.then(() => doDownload());
  }

  // 3) Manual paste-URL form.
  $go.addEventListener("click", doDownload);
  $url.addEventListener("keydown", (e) => {
    if (e.key === "Enter") doDownload();
  });

  function setStatus(text, kind = "") {
    $status.textContent = text;
    $status.className = "card status " + kind;
    $status.classList.remove("hidden");
  }

  function hideStatus() {
    $status.classList.add("hidden");
  }

  function clearResults() {
    $results.innerHTML = "";
    $results.classList.add("hidden");
  }

  function isInstagramUrl(u) {
    return /^https?:\/\/(?:www\.)?instagram\.com\//i.test(u);
  }

  function extractInstagramUrl(text) {
    const m = String(text).match(/https?:\/\/(?:www\.)?instagram\.com\/[^\s]+/i);
    return m ? m[0].replace(/[)\].,;]+$/, "") : "";
  }

  async function doDownload() {
    let url = $url.value.trim();
    if (!url) {
      setStatus("Paste an Instagram URL first.", "error");
      return;
    }
    if (!isInstagramUrl(url)) {
      // try to extract from a shared text blob
      const extracted = extractInstagramUrl(url);
      if (extracted) url = extracted;
      else {
        setStatus("That doesn't look like an Instagram URL.", "error");
        return;
      }
    }

    $go.disabled = true;
    clearResults();
    setStatus("Downloading from Instagram…");

    try {
      const res = await fetch("/api/download", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      });
      const data = await res.json();
      if (!res.ok) {
        const msg = data?.detail || res.statusText || "request failed";
        setStatus(`Error: ${msg}`, "error");
        return;
      }
      if (!data.files?.length) {
        setStatus("No files returned.", "error");
        return;
      }
      hideStatus();
      renderResults(data.files);
    } catch (err) {
      setStatus(`Network error: ${err.message}`, "error");
    } finally {
      $go.disabled = false;
    }
  }

  function renderResults(files) {
    $results.classList.remove("hidden");
    for (const f of files) {
      const card = document.createElement("div");
      card.className = "file";

      // Thumbnail for images, icon for videos.
      const isImage = /\.(jpe?g|png|webp|gif)$/i.test(f.name);
      if (isImage) {
        const img = document.createElement("img");
        img.className = "thumb";
        img.src = f.url;
        img.alt = f.name;
        img.loading = "lazy";
        card.appendChild(img);
      } else {
        const ph = document.createElement("div");
        ph.className = "thumb";
        ph.textContent = "▶";
        ph.style.cssText =
          "display:flex;align-items:center;justify-content:center;color:#94a3b8;font-size:24px;";
        card.appendChild(ph);
      }

      const meta = document.createElement("div");
      meta.className = "meta";
      const name = document.createElement("div");
      name.className = "name";
      name.textContent = f.name;
      const size = document.createElement("div");
      size.className = "size";
      size.textContent = f.size_human;
      meta.appendChild(name);
      meta.appendChild(size);
      card.appendChild(meta);

      const save = document.createElement("button");
      save.className = "save";
      save.textContent = "Save";
      save.addEventListener("click", () => saveFile(f));
      card.appendChild(save);

      $results.appendChild(card);
    }
  }

  async function saveFile(f) {
    // Standard browser save flow: programmatic <a download>.
    // On Android Chrome this writes to the Downloads folder.
    try {
      const res = await fetch(f.url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = f.name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 30_000);
    } catch (err) {
      setStatus(`Save failed: ${err.message}`, "error");
    }
  }

  // Register the service worker (for share-target + offline shell).
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/static/sw.js").catch((err) => {
      console.warn("SW registration failed:", err);
    });
  }
})();
