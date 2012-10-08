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

$(basename "$0") <main_script.py> [parameters]

Runs the PyCascading script locally, without a Hadoop cluster.

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

main_file="$1"
if [ "$main_file" == "" ]; then
    usage
    exit 1
fi

home_dir=$(dirname "$0")
source ${home_dir}/configure_env.sh

if [ "$additional_jars" != "" ]; then
    classpath="$classpath:$additional_jars"
fi

# sys.path will be initialized from JYTHONPATH
java -Xmx512m $JAVA_OPTS -classpath "$classpath" \
com.twitter.pycascading.Main "$home_dir/python/pycascading/bootstrap.py" \
local "$home_dir" "$@"
