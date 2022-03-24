# Create Docker Image from file "Dockerfile"
# STRONGBOX_GIT_DIR=$(realpath $PWD)
# docker build -t archlinux:base-strongbox -f Dockerfile ${STRONGBOX_GIT_DIR}
#
# Create and run docker container
# docker run -it --name strongbox-builder -v ${STRONGBOX_GIT_DIR}:/home/user/strongbox archlinux:base-strongbox
#
# Run already existing container
# docker attach $(docker container start strongbox-builder)
FROM archlinux:base

RUN pacman -Sy
RUN pacman -S binutils \
              fuse2 \
              gtk3 \
              jdk11-openjdk \
              leiningen \
              ttf-dejavu \
              which \
              wget \
              xorg-server-xvfb \
              --noconfirm

RUN useradd user --create-home --user-group --groups wheel --uid 1000

USER user
WORKDIR /home/user
RUN mkdir strongbox

WORKDIR /home/user/strongbox

## export APPIMAGE_EXTRACT_AND_RUN=1
## PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/lib/jvm/java-11-openjdk/bin
## ./build-linux-image.sh
##
## COPY src strongbox/src
## COPY test strongbox/test
## COPY cloverage.clj run-tests.sh project.clj strongbox/
##
## RUN lein deps && lein cloverage -h > /dev/null
##
## RUN ./run-tests.sh
