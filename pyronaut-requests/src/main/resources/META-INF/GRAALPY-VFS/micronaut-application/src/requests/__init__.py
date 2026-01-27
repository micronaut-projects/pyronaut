# requests shim that delegates to pyronaut.requests
import pyronaut.requests as _impl

# re-export API
request = _impl.request
get = _impl.get
post = _impl.post
put = _impl.put
delete = _impl.delete
patch = _impl.patch
head = _impl.head
options = _impl.options
Session = _impl.Session
with_context = _impl.with_context
exceptions = _impl.exceptions
