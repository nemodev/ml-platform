from setuptools import setup, find_packages

setup(
    name="ml_platform_streamlit",
    version="0.1.0",
    packages=find_packages(),
    install_requires=["jupyter_server>=2.0.0"],
    entry_points={
        "jupyter_server.extension": [
            "ml_platform_streamlit = ml_platform_streamlit",
        ]
    },
)
