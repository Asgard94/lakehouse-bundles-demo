"""
setup.py configuration script describing how to build and package this project.

This file is primarily used by the setuptools library and typically should not
be executed directly. See README.md for how to deploy, test, and run
the lending project.
"""
from setuptools import setup, find_packages

import sys
sys.path.append('./src')

import lending

setup(
    name="lending",
    version=lending.__version__,
    url="https://databricks.com",
    author="augusto.pecho@mojix.com",
    description="wheel file based on lending/src",
    packages=find_packages(where='./src'),
    package_dir={'': 'src'},
    entry_points={
        "packages": [
            "main=lending.main:main"
        ]
    },
    install_requires=[
        # Dependencies in case the output wheel file is used as a library dependency.
        # For defining dependencies, when this package is used in Databricks, see:
        # https://docs.databricks.com/dev-tools/bundles/library-dependencies.html
        "setuptools"
    ],
)
