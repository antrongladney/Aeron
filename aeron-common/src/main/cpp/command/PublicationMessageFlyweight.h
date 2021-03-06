/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef INCLUDED_AERON_COMMAND_PUBLICATIONMESSAGEFLYWEIGHT__
#define INCLUDED_AERON_COMMAND_PUBLICATIONMESSAGEFLYWEIGHT__

#include <cstdint>
#include <stddef.h>
#include <common/Flyweight.h>

#include "CorrelatedMessageFlyweight.h"

namespace aeron { namespace common { namespace command {

/**
* Control message for adding or removing a publication
*
* <p>
* 0                   1                   2                   3
* 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
* +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
* |                            Client ID                          |
* |                                                               |
* +---------------------------------------------------------------+
* |                         Correlation ID                        |
* |                                                               |
* +---------------------------------------------------------------+
* |                          Session ID                           |
* +---------------------------------------------------------------+
* |                          Stream ID                            |
* +---------------------------------------------------------------+
* |                        Channel Length                         |
* +---------------------------------------------------------------+
* |                           Channel                            ...
* +---------------------------------------------------------------+
*...                                                              |
* +---------------------------------------------------------------+
*/

#pragma pack(push)
#pragma pack(4)
struct PublicationMessageDefn
{
    CorrelatedMessageDefn correlatedMessage;
    std::int32_t sessionId;
    std::int32_t streamId;
    struct
    {
        std::int32_t channelLength;
        std::int8_t  channelData[1];
    } channel;
};
#pragma pack(pop)


class PublicationMessageFlyweight : public CorrelatedMessageFlyweight
{
public:
    typedef PublicationMessageFlyweight this_t;

    inline PublicationMessageFlyweight(concurrent::AtomicBuffer& buffer, util::index_t offset)
        : CorrelatedMessageFlyweight(buffer, offset), m_struct(overlayStruct<PublicationMessageDefn>(0))
    {
    }

    inline std::int32_t sessionId() const
    {
        return m_struct.sessionId;
    }

    inline this_t& sessionId(std::int32_t value)
    {
        m_struct.sessionId = value;
        return *this;
    }

    inline std::int32_t streamId() const
    {
        return m_struct.streamId;
    }

    inline this_t& streamId(std::int32_t value)
    {
        m_struct.streamId = value;
        return *this;
    }

    inline std::string channel() const
    {
        return stringGet(offsetof(PublicationMessageDefn, channel));
    }

    inline this_t& channel(const std::string& value)
    {
        stringPut(offsetof(PublicationMessageDefn, channel), value);
        return *this;
    }

    util::index_t length()
    {
        return offsetof(PublicationMessageDefn, channel.channelData) + m_struct.channel.channelLength;
    }

private:
    PublicationMessageDefn& m_struct;
};

}}};

#endif