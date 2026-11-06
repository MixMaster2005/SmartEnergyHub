/**
 * Sondage periodique sans chevauchement.
 *
 * - une seule execution a la fois ;
 * - pause automatique quand l'onglet est cache, reprise immediate au retour ;
 * - backoff exponentiel borne en cas d'erreur ;
 * - arret propre (le prochain tick ne s'execute pas).
 */
export function createPoller(task, {
  intervalMs = 5000,
  minBackoffMs = 5000,
  maxBackoffMs = 30000,
  onError = null,
  onSuccess = null,
  runWhenHidden = false,
} = {}) {
  let timer = null;
  let running = false;
  let stopped = true;
  let failures = 0;

  const hidden = () => typeof document !== 'undefined' && document.hidden && !runWhenHidden;

  function schedule(delay) {
    if (stopped) return;
    clearTimeout(timer);
    timer = setTimeout(run, delay);
  }

  function nextDelay() {
    if (failures === 0) return intervalMs;
    return Math.min(maxBackoffMs, minBackoffMs * 2 ** Math.min(failures - 1, 6));
  }

  async function run() {
    if (stopped || running) return;
    if (hidden()) {
      schedule(intervalMs);
      return;
    }
    running = true;
    try {
      await task();
      failures = 0;
      onSuccess?.();
    } catch (error) {
      failures += 1;
      onError?.(error, failures);
    } finally {
      running = false;
      schedule(nextDelay());
    }
  }

  function onVisibility() {
    if (stopped) return;
    if (!hidden()) {
      clearTimeout(timer);
      run();
    }
  }

  return {
    start() {
      if (!stopped) return;
      stopped = false;
      if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibility);
      run();
    },
    stop() {
      stopped = true;
      clearTimeout(timer);
      if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibility);
    },
    refresh() {
      if (stopped) return;
      clearTimeout(timer);
      run();
    },
    get failures() {
      return failures;
    },
    get running() {
      return running;
    },
  };
}
