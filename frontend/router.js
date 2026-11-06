/**
 * Routeur par hash (`#/dashboard`) : aucune route serveur n'est necessaire,
 * donc un rafraichissement ne produit jamais de 404 sur la gateway.
 */

/** Decoupe `#/sensors/SENSOR-001?x=1` en chemin, segments et query. */
export function parseHash(hash) {
  const raw = String(hash ?? '').replace(/^#/, '') || '/dashboard';
  const [pathPart, searchPart = ''] = raw.split('?');
  const segments = pathPart
    .split('/')
    .filter(Boolean)
    .map((segment) => {
      try {
        return decodeURIComponent(segment);
      } catch {
        return segment;
      }
    });
  return {
    path: `/${segments.join('/')}`,
    segments,
    query: new URLSearchParams(searchPart),
  };
}

function matchSegments(pattern, segments) {
  const parts = pattern.split('/').filter(Boolean);
  if (parts.length !== segments.length) return null;
  const params = {};
  for (let index = 0; index < parts.length; index += 1) {
    const part = parts[index];
    if (part.startsWith(':')) {
      if (!segments[index]) return null;
      params[part.slice(1)] = segments[index];
    } else if (part !== segments[index]) {
      return null;
    }
  }
  return params;
}

/** Resout une route (ou null). */
export function resolveRoute(routes, hash) {
  const parsed = parseHash(hash);
  for (const route of routes) {
    const params = matchSegments(route.pattern, parsed.segments);
    if (params) return { ...route, params, query: parsed.query, path: parsed.path };
  }
  return null;
}

/**
 * Cree le routeur.
 * Une vue est une fonction `({mount, params, query, signal, navigate}) => ({destroy}|void)`.
 */
export function createRouter({
  routes,
  mount,
  defaultPath = '/dashboard',
  onNavigate = () => {},
}) {
  let current = null;

  function navigate(path, { replace = false } = {}) {
    const target = `#${path.startsWith('/') ? path : `/${path}`}`;
    if (replace) {
      window.history.replaceState(null, '', target);
      render();
    } else if (window.location.hash === target) {
      render();
    } else {
      window.location.hash = target;
    }
  }

  async function render() {
    const resolved = resolveRoute(routes, window.location.hash)
      || resolveRoute(routes, `#${defaultPath}`);

    if (!resolved) {
      mount.innerHTML = '<div class="empty"><strong>Page inconnue.</strong>'
        + ' <a href="#/dashboard">Retour au tableau de bord</a>.</div>';
      return;
    }

    if (current?.destroy) {
      try {
        current.destroy();
      } catch {
        /* une vue ne doit pas bloquer la navigation */
      }
    }
    current?.controller.abort();

    const controller = new AbortController();
    const context = {
      mount,
      params: resolved.params,
      query: resolved.query,
      signal: controller.signal,
      navigate,
    };
    current = { controller, destroy: null };
    mount.replaceChildren();
    onNavigate(resolved);

    try {
      const result = await resolved.view(context);
      if (current?.controller === controller) {
        current.destroy = typeof result === 'function' ? result : result?.destroy ?? null;
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        mount.innerHTML = `<div class="empty empty--error"><strong>La page n'a pas pu être affichée.</strong>
          <p>${String(error?.message || error)}</p></div>`;
      }
    }
  }

  function start() {
    if (!window.location.hash) {
      window.history.replaceState(null, '', `#${defaultPath}`);
    }
    window.addEventListener('hashchange', render);
    render();
  }

  function stop() {
    window.removeEventListener('hashchange', render);
    current?.controller.abort();
    current?.destroy?.();
  }

  return { start, stop, navigate, refresh: render };
}
