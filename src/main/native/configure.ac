#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

AC_PREREQ([2.63])
AC_INIT

AC_CONFIG_MACRO_DIR([m4])

AC_CONFIG_SRCDIR([src/NativeTask.h])
AC_CONFIG_HEADERS(config.h)

LT_PREREQ([2.2])
LT_INIT
AC_PROG_LIBTOOL

AM_INIT_AUTOMAKE(nativetask,0.1.0)

# Checks for programs.
AC_PROG_CXX(["icpc -static-intel"  g++])
AC_PROG_CC(["icc -static-intel" gcc])

# Checks for libraries.

# Checks for header files.
AC_CHECK_HEADERS([fcntl.h mach/mach.h memory.h stddef.h stdint.h stdlib.h string.h unistd.h])

# Checks for typedefs, structures, and compiler characteristics.
AC_HEADER_STDBOOL
AC_C_INLINE

# Checks for library functions.
AC_FUNC_MALLOC
AC_CHECK_FUNCS([clock_gettime localtime_r memset strchr strtoul])

export PLATFORM=`sh ./getplatform.sh`
AM_CONDITIONAL(PLATFORM_IS_MACOS, [test x"$PLATFORM" = xdarwin])
AM_CONDITIONAL(PLATFORM_IS_LINUX, [test x"$PLATFORM" = xlinux])
AM_CONDITIONAL(PLATFORM_IS_WIN, [test x"$PLATFORM" = xcygwin])

AM_CONDITIONAL(PLATFORM_IS_WIN, [test x"$PLATFORM" = xcygwin])

AC_OUTPUT(Makefile)
