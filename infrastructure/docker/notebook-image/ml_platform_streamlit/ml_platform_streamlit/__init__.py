from .handlers import setup_handlers


def _jupyter_server_extension_points():
    return [{"module": "ml_platform_streamlit"}]


def _patch_proxy_localhost():
    """Fix jupyter-server-proxy IPv6 issue: use 127.0.0.1 instead of localhost."""
    try:
        from jupyter_server_proxy.handlers import LocalProxyHandler, ProxyHandler

        def _proxy_ipv4(self, port, proxied_path):
            return ProxyHandler.proxy(self, "127.0.0.1", port, proxied_path)

        LocalProxyHandler.proxy = _proxy_ipv4
    except ImportError:
        pass


def _load_jupyter_server_extension(server_app):
    _patch_proxy_localhost()
    setup_handlers(server_app.web_app)
    server_app.log.info("ml_platform_streamlit extension loaded")
