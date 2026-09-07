class USSyncError(Exception):
    """Error accionable que se puede presentar sin una traza interna."""


class SessionExpired(USSyncError):
    pass


class AccessDenied(USSyncError):
    pass


class RemoteError(USSyncError):
    pass


class Cancelled(USSyncError):
    pass
