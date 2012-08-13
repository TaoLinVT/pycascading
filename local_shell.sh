#!/usr/bin/env bash

#
# Copyright 2011 Twitter, Inc.
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Runs the PyCascading locally without Hadoop
#


usage()
{
    cat <<EOF
Usage:

$(basename "$0") [parameters]

Runs a jython interactive shell with the Pycascading and hadoop jars loaded.

Options:
   -h                Show this message
   -j <cp>           Additional jar files and Python import folders to be added
                     to the classpath. cp is a list of file and folder locations
                     separated by ":"s

EOF
}


while getopts ":hj:" OPTION; do
    case $OPTION in
        h)  usage
            exit 1
            ;;
        j)  additional_jars="$OPTARG"
            ;;
    esac
done
shift $((OPTIND-1))


home_dir=$(dirname "$0")
source ${home_dir}/configure_env.sh

if [ "$additional_jars" != "" ]; then
    classpath="$classpath:$additional_jars"
fi

# sys.path will be initialized from JYTHONPATH
export CLASSPATH=$CLASSPATH:$classpath
jython -i -Dpython.path="$classpath" \
${home_dir}/python/pycascading/bootstrap_shell.py "${home_dir}/python" "$@"
