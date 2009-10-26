#!/bin/bash -x

#Moves the selected specified files from one directory to another
#for a Subversion working copy.  This script is useful if you have
#a number of different types under one path and just do a bulk svn
#move

set -f
function relocateSvn()
{
  find $1 -name $3 -print | \
  while read F
  do
    LD=${#1}
    CF=${F:$LD}
    FO=`basename $F`
    let L=${#F}-${#FO}-$LD
    NEWDIR=$2/${F:$LD:$L}
    mkdir -p $NEWDIR
  
    find $2 -type d -name '.svn' -prune -o -print | \
    while read D
    do
      if [ ! -d $D ]
      then
        continue
      fi

      if [ ! -d "$D/.svn" ]
      then 
        svn add -q $D
      fi
    done 
  
    svn move $F $NEWDIR/
  done
}

mkdir -p src2/main/java
mkdir -p src2/main/resources
mkdir -p src2/main/groovy
mkdir -p src2/main/webapp
mkdir -p src2/test/java
mkdir -p src2/test/groovy
mkdir -p src2/test/resources
svn add src2
relocateSvn src src2/main/groovy *.groovy
relocateSvn src src2/main/java *.java
relocateSvn src src2/main/resources *.*
relocateSvn test src2/test/groovy *.groovy
relocateSvn test src2/test/java *.java
relocateSvn test src2/test/resources *.*
relocateSvn conf src/main/resources *.*
relocateSvn conf-web src/main/webapp *.*
