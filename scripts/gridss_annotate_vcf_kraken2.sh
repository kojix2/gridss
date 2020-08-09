#!/bin/bash
getopt --test
if [[ ${PIPESTATUS[0]} -ne 4 ]]; then
	echo 'WARNING: "getopt --test"` failed in this environment.' 1>&2
	echo "WARNING: The version of getopt(1) installed on this system might not be compatible with the GRIDSS driver script." 1>&2
fi
set -o errexit -o pipefail -o noclobber -o nounset
last_command=""
current_command=""
trap 'last_command=$current_command; current_command=$BASH_COMMAND' DEBUG
trap 'echo "\"${last_command}\" command completed with exit code $?."' EXIT
#253 forcing C locale for everything
export LC_ALL=C

EX_USAGE=64
EX_NOINPUT=66
EX_CANTCREAT=73
EX_CONFIG=78

USAGE_MESSAGE="
Usage: gridss_annotate_vcf_kraken2.sh [--db standard] -o output.vcf input.vcf
	-o,--output: output vcf file. Defaults to stdout.
	-j/--jar: location of GRIDSS jar
	--db: kraken2 database
	--threads: number of threads to use. Defaults to the number of cores available.
	--kraken2: kraken2 executable. (Default: kraken2)
	--kraken2args: additional kraken2 arguments
	--minlength: minimum length of inserted sequence to annotate. (Default: 20)
	"
OPTIONS=o:t:j:
LONGOPTS=db:,threads:,kraken2:,kraken2args:,jar:,minlength:
! PARSED=$(getopt --options=$OPTIONS --longoptions=$LONGOPTS --name "$0" -- "$@")
if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
    # e.g. return value is 1
    #  then getopt has complained about wrong arguments to stdout
	echo "$USAGE_MESSAGE" 1>&2
    exit $EX_USAGE
fi
eval set -- "$PARSED"
db=""
output="/dev/stdout"
threads=$(nproc)
kraken2="kraken2"
kraken2args=""
minlength="20"
while true; do
    case "$1" in
        -o|--output)
            output="$2"
            shift 2
            ;;
		-j|--jar)
            GRIDSS_JAR="$2"
            shift 2
            ;;
		--db)
            db="$2"
            shift 2
            ;;
		-t|--threads)
			printf -v threads '%d\n' "$2" 2>/dev/null
			printf -v threads '%d' "$2" 2>/dev/null
			shift 2
			;;
		--minlength)
			printf -v minlength '%d\n' "$2" 2>/dev/null
			printf -v minlength '%d' "$2" 2>/dev/null
			shift 2
			;;
		--kraken2)
			kraken2=$2
			shift 2
			;;
		--kraken2args)
			kraken2args=$2
			shift 2
			;;
		--)
            shift
            break
            ;;
        *)
            echo "Programming error"
            exit 1
            ;;
    esac
done
write_status() {
	echo "$(date): $1" 1>&2
}
if [[ "$#" != "1" ]] ; then
	echo "$USAGE_MESSAGE"
	exit $EX_USAGE
fi
if [[ "$1" == "" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Missing input vcf" 
	exit $EX_USAGE
fi
if [[ ! -f $1 ]] ; then
	
	echo "$USAGE_MESSAGE" 1>&2
	write_status "Input file '$1' not found." 1>&2
	exit $EX_NOINPUT
fi
### Find the jars
find_jar() {
	env_name=$1
	if [[ -f "${!env_name:-}" ]] ; then
		echo "${!env_name}"
	else
		write_status "Unable to find $2 jar. Specify using the environment variant $env_name, or the --jar command line parameter."
		exit $EX_NOINPUT
	fi
}
gridss_jar=$(find_jar GRIDSS_JAR gridss)
##### --threads
if [[ "$threads" -lt 1 ]] ; then
	write_status "$USAGE_MESSAGE"
	write_status "Illegal thread count: $threads. Specify an integer thread count using the --threads command line argument"
	exit $EX_USAGE
fi
write_status  "Using $threads worker threads."
if [[ "$db" == "" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Missing Kraken2 database location. Specify with --db"
	exit $EX_USAGE
fi
if [[ ! -d "$db" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Unable to find kraken2 database directory '$db'" 
	exit $EX_NOINPUT
fi
if [[ "$output" == "" ]] ; then
	echo "$USAGE_MESSAGE"
	write_status "Missing output vcf. Specify with --output"
	exit $EX_USAGE
fi
# Validate tools exist on path
for tool in $kraken2 java ; do
	if ! which $tool >/dev/null; then
		write_status "Error: unable to find $tool on \$PATH"
		exit $EX_CONFIG
	fi
	write_status "Found $(which $tool)"
done
write_status "kraken version: $($kraken2 --version 2>&1 | head -1)"
write_status "bash version: $(/bin/bash --version 2>&1 | head -1)"

# check java version is ok using the gridss.Echo entry point
if java -cp $gridss_jar gridss.Echo ; then
	write_status "java version: $(java -version 2>&1)"
else
	write_status "Unable to run GRIDSS jar. GRIDSS requires java 1.8 or later."
	write_status "java version: $(java -version  2>&1)"
	exit $EX_CONFIG
fi

unset DISPLAY # Prevents errors attempting to connecting to an X server when starting the R plotting device

jvm_args=" \
	-Dpicard.useLegacyParser=false \
	-Dsamjdk.use_async_io_read_samtools=true \
	-Dsamjdk.use_async_io_write_samtools=true \
	-Dsamjdk.use_async_io_write_tribble=true \
	-Dsamjdk.buffer_size=4194304 \
	-Dsamjdk.async_io_read_threads=$threads"

java -Xmx64m $jvm_args -cp $gridss_jar gridss.InsertedSequencesToFasta \
	-INPUT "$1" \
	-OUTPUT /dev/stdout \
	-MIN_SEQUENCE_LENGTH $minlength \
| $kraken2 \
	--threads $threads \
	--db $db \
	--output /dev/stdout \
	$kraken2args \
	/dev/stdin \
| java -Xmx128m $jvm_args -cp $gridss_jar gridss.kraken.AnnotateVariantsKraken \
	-INPUT "$1" \
	-OUTPUT "$output" \
	-KRAKEN_INPUT /dev/stdin

trap - EXIT
exit 0 # success!