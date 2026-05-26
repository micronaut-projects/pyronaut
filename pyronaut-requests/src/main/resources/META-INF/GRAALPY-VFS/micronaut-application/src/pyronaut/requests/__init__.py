# pyronaut.requests - requests-compatible API over Micronaut HttpClient
import atexit
import base64
import os
from urllib.parse import urlencode, urlparse
from typing import Any, Dict, Optional, Tuple, Union, Protocol, cast, TYPE_CHECKING
from . import exceptions as _exc

# GraalPy interop shim for static analyzers
try:
    import java as _java_mod  # type: ignore[attr-defined]
except Exception:  # pragma: no cover - static typing fallback
    class _Any:
        def __getattr__(self, name):
            return self
        def __call__(self, *args, **kwargs):
            return self
    class _Dummy:
        @staticmethod
        def type(name: str):
            return _Any()
    _java_mod = _Dummy()

class _JavaProto(Protocol):
    @staticmethod
    def type(name: str): ...

java = cast(_JavaProto, _java_mod)

def jtype(name: str):
    try:
        import java as j
        return j.type(name)
    except Exception:
        class _Any:
            def __getattr__(self, n):
                return self
            def __call__(self, *a, **k):
                return self
        return _Any()

# Bind Java types (stubs under non-GraalPy)
HttpClient = jtype("io.micronaut.http.client.HttpClient")
HttpRequest = jtype("io.micronaut.http.HttpRequest")
HttpMethod = jtype("io.micronaut.http.HttpMethod")
JsonMapper = jtype("io.micronaut.json.JsonMapper")
ClientRegistry = jtype("io.micronaut.pyronaut.requests.ClientRegistry")
UriBuilder = jtype("io.micronaut.http.uri.UriBuilder")
HttpClientConfiguration = jtype("io.micronaut.http.client.HttpClientConfiguration")
JByteArray = jtype("byte[]")
JString = jtype("java.lang.String")
URL = jtype("java.net.URL")
Duration = jtype("java.time.Duration")
EmbeddedServer = jtype("io.micronaut.runtime.server.EmbeddedServer")
HttpClientInvoker = jtype("io.micronaut.pyronaut.requests.HttpClientInvoker")
System = jtype("java.lang.System")


def _jstring(value) -> Any:
    return JString(str(value))


def _parse_proxy_url(value: str):
    try:
        u = urlparse(value)
        host = u.hostname or ''
        port = u.port or (443 if u.scheme == 'https' else 80)
        return host, str(port)
    except Exception:
        return '', ''


def _collect_env_proxies():
    m = {}
    for key in ("HTTP_PROXY", "http_proxy"):
        v = os.environ.get(key)
        if v:
            m['http'] = v
            break
    for key in ("HTTPS_PROXY", "https_proxy"):
        v = os.environ.get(key)
        if v:
            m['https'] = v
            break
    for key in ("NO_PROXY", "no_proxy"):
        v = os.environ.get(key)
        if v:
            m['no_proxy'] = v
            break
    return m


def _apply_proxy_system_properties(mapping: Dict[str, str]):
    # returns dict of previous values to restore
    prev = {}
    try:
        if not mapping:
            return prev
        http_v = mapping.get('http')
        https_v = mapping.get('https')
        no_proxy_v = mapping.get('no_proxy') or mapping.get('no-proxy') or mapping.get('noproxy')
        if http_v:
            h, p = _parse_proxy_url(http_v)
            if h:
                prev['http.proxyHost'] = System.getProperty('http.proxyHost')
                prev['http.proxyPort'] = System.getProperty('http.proxyPort')
                System.setProperty('http.proxyHost', h)
                System.setProperty('http.proxyPort', p)
        if https_v:
            h, p = _parse_proxy_url(https_v)
            if h:
                prev['https.proxyHost'] = System.getProperty('https.proxyHost')
                prev['https.proxyPort'] = System.getProperty('https.proxyPort')
                System.setProperty('https.proxyHost', h)
                System.setProperty('https.proxyPort', p)
        if no_proxy_v:
            # transform comma list into '|' separated for java nonProxyHosts
            hosts = [s.strip() for s in no_proxy_v.split(',') if s.strip()]
            if hosts:
                prev['http.nonProxyHosts'] = System.getProperty('http.nonProxyHosts')
                System.setProperty('http.nonProxyHosts', '|'.join(hosts))
    except Exception:
        return prev
    return prev


def _restore_system_properties(prev: Dict[str, Optional[str]]):
    try:
        for k, v in (prev or {}).items():
            if v is None:
                System.clearProperty(k)
            else:
                System.setProperty(k, v)
    except Exception:
        pass


def _flatten_params(params):
    if not params:
        return []
    if hasattr(params, 'items') and callable(getattr(params, 'items')):
        iterable = params.items()
    else:
        iterable = params
    pairs = []
    for item in iterable:
        k, v = item
        if v is None:
            continue
        if isinstance(v, (list, tuple)):
            for value in v:
                if value is not None:
                    pairs.append((k, value))
        else:
            pairs.append((k, v))
    return pairs


def _merge_params(session_params, request_params):
    if (
        session_params
        and request_params
        and hasattr(session_params, 'items')
        and callable(getattr(session_params, 'items'))
        and hasattr(request_params, 'items')
        and callable(getattr(request_params, 'items'))
    ):
        merged = {}
        merged.update(session_params)
        merged.update(request_params)
        return _flatten_params(merged)
    pairs = []
    pairs.extend(_flatten_params(session_params))
    pairs.extend(_flatten_params(request_params))
    return pairs


# expose exceptions module for compatibility
exceptions = _exc

# helper: convert Java Map/List to native Python
def _to_python_native(obj):
    MapCls = jtype("java.util.Map")
    ListCls = jtype("java.util.List")
    # Duck-type on methods to avoid instanceof on foreign classes
    if hasattr(obj, 'entrySet') and callable(getattr(obj, 'entrySet')):
        py = {}
        try:
            entries = obj.entrySet().toArray()
            for e in entries:
                k = e.getKey()
                v = e.getValue()
                py[_to_python_native(k)] = _to_python_native(v)
            return py
        except Exception:
            pass
    if hasattr(obj, 'toArray') and callable(getattr(obj, 'toArray')):
        try:
            arr = obj.toArray()
            return [_to_python_native(x) for x in arr]
        except Exception:
            pass
    # Primitive wrappers fall through
    return obj

DEFAULT_MAX_REDIRECTS = 30

# module-level default client and mapper
_default_client = None
_default_mapper = None


def _ensure_default_client():
    global _default_client
    if _default_client is None:
        _default_client = HttpClient.create(None)
        ClientRegistry.register(_default_client)
    return _default_client


def _ensure_default_mapper():
    global _default_mapper
    if _default_mapper is None:
        _default_mapper = JsonMapper.createDefault()
    return _default_mapper


class CaseInsensitiveDict:
    def __init__(self, *args, **kwargs):
        super().__init__()
        self._store = {}
        if args:
            for k, v in dict(*args, **kwargs).items():
                self[k] = v
        else:
            for k, v in kwargs.items():
                self[k] = v

    def __setitem__(self, key, value):
        self._store[key.lower()] = (key, value)

    def __getitem__(self, key):
        return self._store[key.lower()][1]

    def get(self, key, default=None):
        return self._store.get(key.lower(), (None, default))[1]

    def items(self):
        return ((k, v) for k, (k, v) in self._store.items())

    def __contains__(self, key):
        return key.lower() in self._store


def _response_body_bytes(body) -> bytes:
    if isinstance(body, (bytes, bytearray)):
        return bytes(body)
    return bytes(int(value) & 0xFF for value in body)


class Response:
    def __init__(self, request_url: str, resp, history=None, body=None):
        self._resp = resp
        self.url = request_url
        self.status_code = resp.getStatus().getCode()
        self.reason = str(resp.getStatus().getReason()) if hasattr(resp, 'getStatus') else ''
        # Flatten multi-value headers by taking the first value
        hdrs = {}
        amap = resp.getHeaders().asMap()
        for k in amap.keySet().toArray():
            values = amap.get(k)
            if values is not None and not values.isEmpty():
                hdrs[str(k)] = str(values.get(0))
        self.headers = CaseInsensitiveDict(hdrs)
        if body is not None:
            self._content = _response_body_bytes(body)
        else:
            bodyOpt = resp.getBody()
            if bodyOpt is None or not bodyOpt.isPresent():
                self._content = b""
            else:
                arr = bodyOpt.get()
                self._content = _response_body_bytes(arr)
        self.history = history or []


    @property
    def ok(self) -> bool:
        return 200 <= self.status_code < 400

    @property
    def content(self) -> bytes:
        return self._content

    @property
    def text(self) -> str:
        ct = self.headers.get('Content-Type') or ''
        charset = 'utf-8'
        if 'charset=' in ct:
            try:
                charset = ct.split('charset=')[-1].split(';')[0].strip()
            except Exception:
                pass
        try:
            return self._content.decode(charset, errors='replace')
        except Exception:
            return self._content.decode('utf-8', errors='replace')

    def json(self, type: Optional[type] = None):
        mapper = _ensure_default_mapper()
        if type is None:
            # Always return native Python dict/list
            try:
                Map = jtype("java.util.Map")
                List = jtype("java.util.List")
                obj = None
                try:
                    obj = mapper.readValue(self.text, Map)
                except BaseException:
                    try:
                        obj = mapper.readValue(self.text, List)
                    except BaseException:
                        obj = None
                if obj is not None:
                    return _to_python_native(obj)
            except Exception:
                pass
            import json as _py_json
            return _py_json.loads(self.text)
        else:
            # resolve Java class for type
            cls = type
            module = getattr(cls, '__module__', None) or ''
            qualname = getattr(cls, '__qualname__', getattr(cls, '__name__', ''))
            candidates = []
            if not module or module == '__main__' or module.startswith('__'):
                candidates.append(f"python.{cls.__name__}")
            else:
                candidates.append(f"{module}.{qualname}")
            candidates.append(f"python.{cls.__name__}")

            target = None
            for fqn in candidates:
                try:
                    target = jtype(fqn)
                    break
                except BaseException:
                    continue
            if target is None:
                raise TypeError(f"Cannot resolve Java type for {cls}")
            obj = mapper.readValue(self.text, target)
            if hasattr(obj, 'asPolyglotValue'):
                return obj.asPolyglotValue()
            return obj

    def raise_for_status(self):
        if 400 <= self.status_code:
            msg = f"{self.status_code} Error: {self.reason} for url: {self.url}"
            raise _exc.HTTPError(msg, response=self)


class Session:
    def __init__(self, base_url: Optional[str] = None, mapper=None, register: bool = True):
        self.base_url = base_url
        self._client = HttpClient.create(None if base_url is None else java.net.URL(base_url))
        if register:
            ClientRegistry.register(self._client)
        self._mapper = mapper or _ensure_default_mapper()
        self.headers: Dict[str, str] = {}
        self.auth = None
        self.cookies: Dict[str, str] = {}
        self.params: Dict[str, Any] = {}
        self.verify: Optional[bool] = None
        self.proxies: Optional[Dict[str, str]] = None
        self.trust_env: bool = True
        self.hooks: Dict[str, list] = {"response": []}

    def close(self):
        try:
            self._client.close()
        except BaseException:
            pass

    # Core request method
    def request(self, method: str, url: str, params: Dict[str, Any] = None, data: Any = None,
                json: Any = None, headers: Dict[str, str] = None, cookies: Dict[str, str] = None,
                auth: Optional[Tuple[str, str]] = None, timeout: Optional[Union[float, Tuple[float, float]]] = None,
                allow_redirects: bool = True, verify: Optional[Union[bool, str]] = None):
        full_url = self._resolve_url(url)
        req = HttpRequest.create(HttpMethod.valueOf(method.upper()), _jstring(full_url))
        # headers
        merged_headers: Dict[str, str] = {}
        merged_headers.update(self.headers or {})
        if headers:
            merged_headers.update(headers)
        # cookies: merge session cookies with per-call cookies
        merged_cookies: Dict[str, str] = {}
        if getattr(self, 'cookies', None):
            merged_cookies.update(self.cookies)
        if cookies:
            merged_cookies.update(cookies)
        if merged_cookies:
            merged_headers['Cookie'] = '; '.join([f"{k}={v}" for k, v in merged_cookies.items()])
        if auth or self.auth:
            user_pass = auth or self.auth
            up = (user_pass[0] + ':' + user_pass[1]).encode('utf-8')
            merged_headers['Authorization'] = 'Basic ' + base64.b64encode(up).decode('ascii')

        merged_params = _merge_params(getattr(self, 'params', None), params)
        if merged_params:
            uri = UriBuilder.of(_jstring(full_url))
            for k, v in merged_params:
                uri = uri.queryParam(_jstring(k), _jstring(v))
            full_url = str(uri.build())
            req = HttpRequest.create(HttpMethod.valueOf(method.upper()), _jstring(full_url))

        # body
        content_type_set = False
        if json is not None:
            body_bytes = self._mapper.writeValueAsBytes(json)
            req = req.body(body_bytes)
            merged_headers['Content-Type'] = 'application/json'
            content_type_set = True
        elif data is not None:
            body = None
            if isinstance(data, (bytes, bytearray)):
                body = JByteArray(bytes(data))
            elif isinstance(data, str):
                body = _jstring(data)
                if not content_type_set:
                    merged_headers.setdefault('Content-Type', 'text/plain; charset=utf-8')
            elif isinstance(data, dict):
                # form
                body = _jstring(urlencode(data))
                merged_headers.setdefault('Content-Type', 'application/x-www-form-urlencoded')
            else:
                # attempt JSON
                body = self._mapper.writeValueAsBytes(data)
                merged_headers.setdefault('Content-Type', 'application/json')
            req = req.body(body)

        # apply headers
        for k, v in (merged_headers or {}).items():
            req = req.header(_jstring(k), _jstring(v))

        # per-call timeout client
        client_to_use = self._client
        temp_client = None
        eff_verify = verify if verify is not None else getattr(self, 'verify', None)
        needs_custom_cfg = timeout is not None or (eff_verify is False) or (not allow_redirects)
        if needs_custom_cfg:
            DefaultHttpClientConfiguration = jtype("io.micronaut.http.client.DefaultHttpClientConfiguration")
            cfg = DefaultHttpClientConfiguration()
            import math
            if timeout is not None:
                if isinstance(timeout, tuple):
                    connect, read = timeout
                else:
                    connect, read = timeout, timeout
                if connect is not None:
                    cfg.setConnectTimeout(Duration.ofMillis(int(math.ceil(connect * 1000))))
                if read is not None:
                    cfg.setReadTimeout(Duration.ofMillis(int(math.ceil(read * 1000))))
                    cfg.setRequestTimeout(Duration.ofMillis(int(math.ceil((read * 1000) + 1000))))
            if eff_verify is False:
                try:
                    sslCfg = cfg.getSslConfiguration()
                    if sslCfg is not None and hasattr(sslCfg, 'setInsecureTrustAllCertificates'):
                        sslCfg.setInsecureTrustAllCertificates(True)
                except Exception:
                    pass
            if not allow_redirects:
                cfg.setFollowRedirects(False)
            temp_client = HttpClient.create(None if self.base_url is None else URL(self.base_url), cfg)
            ClientRegistry.register(temp_client)
            client_to_use = temp_client

        blocking = client_to_use.toBlocking()
        # Map any client exception that carries a response into a Response (requests doesn't raise by default)
        # proxies via env/system properties if enabled
        prev_props = None
        try:
            mapping = {}
            if getattr(self, 'trust_env', True):
                mapping.update(_collect_env_proxies())
            if getattr(self, 'proxies', None):
                mapping.update(self.proxies)
            prev_props = _apply_proxy_system_properties(mapping)
        except Exception:
            prev_props = None

        # Use Java invoker to prevent ForeignException crossing into pytest
        result = HttpClientInvoker.exchange(client_to_use, req, JByteArray)
        if getattr(result, 'success', False) or getattr(result, 'response', None) is not None:
            resp = result.response
            response = Response(full_url, resp, history=[], body=getattr(result, 'body', None))
            try:
                setCookies = resp.getHeaders().getAll("Set-Cookie")
                if setCookies is not None:
                    arr = setCookies.toArray()
                    for sc in arr:
                        s = str(sc)
                        if '=' in s:
                            pair = s.split(';', 1)[0]
                            if '=' in pair:
                                ck, cv = pair.split('=', 1)
                                if getattr(self, 'cookies', None) is not None:
                                    self.cookies[ck] = cv
            except Exception:
                pass
        else:
            # No response to build from; raise a Python exception mapped to requests-like types
            message = getattr(result, 'message', None) or 'No response from client invoker'
            exclass = (getattr(result, 'exceptionClass', None) or 'HttpClientError')
            low = f"{exclass} {message}".lower()
            if 'timeout' in low:
                raise _exc.Timeout(message)
            if 'connection' in low or 'closed' in low or 'unavailable' in low:
                raise _exc.ConnectionError(message)
            raise _exc.RequestException(message)

        # manual redirects with history and requests-like semantics
        redirects = 0
        history = []
        while bool(allow_redirects) and response.status_code in (301, 302, 303, 307, 308) and redirects < DEFAULT_MAX_REDIRECTS:
            redirects += 1
            location = response.headers.get('Location')
            if not location:
                break
            # Append current response to history
            history.append(response)
            # 303 -> GET; 301/302 -> GET if original was not GET/HEAD
            if response.status_code == 303 or (response.status_code in (301, 302) and method.upper() not in ('GET', 'HEAD')):
                method = 'GET'
                req = HttpRequest.create(HttpMethod.valueOf('GET'), _jstring(self._resolve_url(location)))
            else:
                req = HttpRequest.create(HttpMethod.valueOf(method.upper()), _jstring(self._resolve_url(location)))
            # reapply headers (no body on GET)
            for k, v in (merged_headers or {}).items():
                req = req.header(_jstring(k), _jstring(v))
            r2 = HttpClientInvoker.exchange(client_to_use, req, JByteArray)
            if getattr(r2, 'success', False) or getattr(r2, 'response', None) is not None:
                resp2 = r2.response
                response = Response(self._resolve_url(location), resp2, history=list(history), body=getattr(r2, 'body', None))
                try:
                    setCookies = resp2.getHeaders().getAll("Set-Cookie")
                    if setCookies is not None:
                        arr = setCookies.toArray()
                        for sc in arr:
                            s = str(sc)
                            if '=' in s:
                                pair = s.split(';', 1)[0]
                                if '=' in pair:
                                    ck, cv = pair.split('=', 1)
                                    if getattr(self, 'cookies', None) is not None:
                                        self.cookies[ck] = cv
                except Exception:
                    pass
            else:
                message = getattr(r2, 'message', None) or 'No response from client invoker'
                exclass = (getattr(r2, 'exceptionClass', None) or 'HttpClientError')
                low = f"{exclass} {message}".lower()
                if 'timeout' in low:
                    raise _exc.Timeout(message)
                if 'connection' in low or 'closed' in low or 'unavailable' in low:
                    raise _exc.ConnectionError(message)
                raise _exc.RequestException(message)

        if bool(allow_redirects) and response.status_code in (301, 302, 303, 307, 308) and redirects >= DEFAULT_MAX_REDIRECTS:
            raise _exc.TooManyRedirects(f"Exceeded {DEFAULT_MAX_REDIRECTS} redirects for {url}")

        try:
            hooks = getattr(self, 'hooks', None)
            if hooks and isinstance(hooks, dict):
                resphooks = hooks.get('response') or []
                for h in resphooks:
                    try:
                        h(response)
                    except Exception:
                        pass
        except Exception:
            pass

        # cleanup temp client
        if temp_client is not None:
            try:
                ClientRegistry.unregister(temp_client)
                temp_client.close()
            except BaseException:
                pass

        # restore proxy properties
        try:
            if prev_props is not None:
                _restore_system_properties(prev_props)
        except Exception:
            pass

        return response

    # convenience methods
    def get(self, url, **kwargs):
        return self.request('GET', url, **kwargs)

    def post(self, url, **kwargs):
        return self.request('POST', url, **kwargs)

    def put(self, url, **kwargs):
        return self.request('PUT', url, **kwargs)

    def delete(self, url, **kwargs):
        return self.request('DELETE', url, **kwargs)

    def patch(self, url, **kwargs):
        return self.request('PATCH', url, **kwargs)

    def head(self, url, **kwargs):
        if 'allow_redirects' not in kwargs:
            kwargs['allow_redirects'] = False
        return self.request('HEAD', url, **kwargs)

    def options(self, url, **kwargs):
        return self.request('OPTIONS', url, **kwargs)

    def _resolve_url(self, url: str) -> str:
        if self.base_url and (url.startswith('/') or not url.startswith('http')):
            base = self.base_url.rstrip('/')
            path = url if url.startswith('/') else '/' + url
            return base + path
        return url


# Top-level module API

_default_session = None


def request(method: str, url: str, **kwargs):
    global _default_session
    if _default_session is None:
        # default session uses a long-lived client; do not register with context-bound registry
        _default_session = Session(register=False)
    return _default_session.request(method, url, **kwargs)


def get(url: str, **kwargs):
    return request('GET', url, **kwargs)


def post(url: str, **kwargs):
    return request('POST', url, **kwargs)


def put(url: str, **kwargs):
    return request('PUT', url, **kwargs)


def delete(url: str, **kwargs):
    return request('DELETE', url, **kwargs)


def patch(url: str, **kwargs):
    return request('PATCH', url, **kwargs)


def head(url: str, **kwargs):
    return request('HEAD', url, **kwargs)


def options(url: str, **kwargs):
    return request('OPTIONS', url, **kwargs)


def with_context(ctx):
    # Resolve server safely via wrapper to avoid ForeignException crossing
    server = ctx["io.micronaut.runtime.server.EmbeddedServer"]
    base_url = f"http://localhost:{server.getPort()}"
    # resolve mapper from context if present
    try:
        mapper = ctx["io.micronaut.json.JsonMapper"]
    except KeyError:
        mapper = _ensure_default_mapper()
    return Session(base_url, mapper)


# atexit fallback close all
@atexit.register
def _shutdown():
    try:
        ClientRegistry.closeAll()
    except BaseException:
        pass
