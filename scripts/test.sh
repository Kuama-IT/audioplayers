#!/usr/bin/env bash

echo "Starting Flame Tester"
echo "---------------------"
for file in $(find . -type d -name "test"); do
  dir=$(dirname $file)
  cd $dir
  if [ -f "./pubspec.yaml" ]; then
    echo "Testing $dir"
    flutter test
    test_result=$?
    if [ $test_result -ne 0 ]; then
      exit $test_result
    fi
  fi
  cd $(cd -)
done

exit 0
