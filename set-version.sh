#!/bin/bash

# Get current branch name
BRANCH=$(git branch --show-current)

# Get current date in YYYYMMDD format
DATE=$(date +%Y%m%d)

# Set version based on branch
if [[ "$BRANCH" == "main" || "$BRANCH" == "master" ]]; then
    VERSION="$DATE"
else
    # Replace any special characters in branch name with hyphens
    CLEAN_BRANCH=$(echo "$BRANCH" | sed 's/[^a-zA-Z0-9]/-/g')
    VERSION="$DATE-$CLEAN_BRANCH"
fi

echo "Setting version to: $VERSION"

# Update the changelist property in pom.xml
sed -i.bak "s/<changelist>.*<\/changelist>/<changelist>$VERSION<\/changelist>/" pom.xml

echo "Version updated in pom.xml" 